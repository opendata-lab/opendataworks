#!/usr/bin/env python3
"""Template binding, the predicate DSL, and the restricted expression evaluator.

Three placeholder forms may appear in a node's ``sql`` template, each carrying a
different safety contract:

``{{ expr }}``   checked value — the result must be a scalar or an array of
                 scalars. Scalars are escaped and quoted, arrays expand to a
                 comma list. A value can never escape its literal position.
``{{! expr }}``  identifier fragment — spliced verbatim, so it is restricted to
                 a bare identifier (or a value declared in a parameter's
                 ``values`` enum). Anything else is rejected.
``{{? name }}``  predicate fragment — compiled from the node's ``predicates``
                 map. Sub-predicates whose bound value is null are dropped, so an
                 absent optional parameter simply makes its filter disappear.

Expressions are evaluated by walking a whitelisted subset of the Python AST.
``eval``/``exec`` are never used, and attribute access resolves through mapping
lookups rather than ``getattr``, so there is no path from an expression to a
Python object's internals.
"""

from __future__ import annotations

import ast
import datetime as _dt
import re
from typing import Any, Callable, Dict, List, Mapping, Sequence


class BindingError(ValueError):
    """Raised for any template, expression or predicate problem."""


# One pass over every placeholder form, dispatched on the leading marker:
# no marker = checked value, ``!`` = identifier fragment, ``?`` = predicate.
PLACEHOLDER_RE = re.compile(r"\{\{\s*(?P<marker>[!?])?\s*(?P<body>.*?)\s*\}\}", re.DOTALL)

IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$")
PREDICATE_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


# --------------------------------------------------------------------------
# Restricted expression evaluation
# --------------------------------------------------------------------------

def _fn_pluck(rows: Any, field: str) -> List[Any]:
    """Project one field out of a list of row mappings, dropping nulls."""
    if not isinstance(rows, Sequence) or isinstance(rows, (str, bytes)):
        raise BindingError(f"pluck() 的第一个参数必须是行列表，实际是 {type(rows).__name__}")
    out: List[Any] = []
    for row in rows:
        if not isinstance(row, Mapping):
            raise BindingError("pluck() 只能作用于字典行")
        value = row.get(field)
        if value is not None:
            out.append(value)
    return out


def _fn_coalesce(*values: Any) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def _fn_distinct(values: Any) -> List[Any]:
    if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
        raise BindingError("distinct() 的参数必须是列表")
    seen: List[Any] = []
    for value in values:
        if value not in seen:
            seen.append(value)
    return seen


ALLOWED_FUNCTIONS: Dict[str, Callable[..., Any]] = {
    "pluck": _fn_pluck,
    "coalesce": _fn_coalesce,
    "distinct": _fn_distinct,
    "len": lambda value: len(value),
    "abs": abs,
    "round": round,
    "min": min,
    "max": max,
    "sum": sum,
    "int": int,
    "float": float,
    "str": str,
    "lower": lambda value: str(value).lower(),
    "upper": lambda value: str(value).upper(),
}

_ALLOWED_NODES = (
    ast.Expression,
    ast.Constant,
    ast.Name,
    ast.Load,
    ast.Attribute,
    ast.Subscript,
    ast.BinOp,
    ast.UnaryOp,
    ast.BoolOp,
    ast.Compare,
    ast.IfExp,
    ast.Call,
    ast.List,
    ast.Tuple,
    ast.Dict,
    ast.Add,
    ast.Sub,
    ast.Mult,
    ast.Div,
    ast.FloorDiv,
    ast.Mod,
    ast.USub,
    ast.UAdd,
    ast.Not,
    ast.And,
    ast.Or,
    ast.Eq,
    ast.NotEq,
    ast.Lt,
    ast.LtE,
    ast.Gt,
    ast.GtE,
    ast.In,
    ast.NotIn,
    ast.Is,
    ast.IsNot,
)

_BIN_OPS: Dict[type, Callable[[Any, Any], Any]] = {
    ast.Add: lambda a, b: a + b,
    ast.Sub: lambda a, b: a - b,
    ast.Mult: lambda a, b: a * b,
    ast.Div: lambda a, b: a / b,
    ast.FloorDiv: lambda a, b: a // b,
    ast.Mod: lambda a, b: a % b,
}

_COMPARE_OPS: Dict[type, Callable[[Any, Any], Any]] = {
    ast.Eq: lambda a, b: a == b,
    ast.NotEq: lambda a, b: a != b,
    ast.Lt: lambda a, b: a < b,
    ast.LtE: lambda a, b: a <= b,
    ast.Gt: lambda a, b: a > b,
    ast.GtE: lambda a, b: a >= b,
    ast.In: lambda a, b: a in b,
    ast.NotIn: lambda a, b: a not in b,
    ast.Is: lambda a, b: a is b,
    ast.IsNot: lambda a, b: a is not b,
}


def _lookup(container: Any, key: str, where: str) -> Any:
    if isinstance(container, Mapping):
        if key not in container:
            raise BindingError(f"{where}: 找不到字段 `{key}`")
        return container[key]
    raise BindingError(f"{where}: `{key}` 只能作用于字典，实际是 {type(container).__name__}")


def _eval_node(node: ast.AST, context: Mapping[str, Any], source: str) -> Any:
    if not isinstance(node, _ALLOWED_NODES):
        raise BindingError(f"表达式 `{source}` 使用了不允许的语法 {type(node).__name__}")

    if isinstance(node, ast.Expression):
        return _eval_node(node.body, context, source)
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Name):
        if node.id in context:
            return context[node.id]
        raise BindingError(f"表达式 `{source}`: 未知的名字 `{node.id}`")
    if isinstance(node, ast.Attribute):
        base = _eval_node(node.value, context, source)
        return _lookup(base, node.attr, f"表达式 `{source}`")
    if isinstance(node, ast.Subscript):
        base = _eval_node(node.value, context, source)
        key = _eval_node(node.slice, context, source)
        if isinstance(base, Mapping):
            return _lookup(base, key, f"表达式 `{source}`")
        if isinstance(base, Sequence) and isinstance(key, int):
            try:
                return base[key]
            except IndexError as exc:
                raise BindingError(f"表达式 `{source}`: 下标越界") from exc
        raise BindingError(f"表达式 `{source}`: 无法对 {type(base).__name__} 取下标")
    if isinstance(node, ast.BinOp):
        handler = _BIN_OPS.get(type(node.op))
        if handler is None:
            raise BindingError(f"表达式 `{source}`: 不允许的运算符 {type(node.op).__name__}")
        return handler(_eval_node(node.left, context, source), _eval_node(node.right, context, source))
    if isinstance(node, ast.UnaryOp):
        operand = _eval_node(node.operand, context, source)
        if isinstance(node.op, ast.USub):
            return -operand
        if isinstance(node.op, ast.UAdd):
            return +operand
        return not operand
    if isinstance(node, ast.BoolOp):
        values = [_eval_node(value, context, source) for value in node.values]
        if isinstance(node.op, ast.And):
            result: Any = True
            for value in values:
                if not value:
                    return value
                result = value
            return result
        for value in values:
            if value:
                return value
        return values[-1] if values else False
    if isinstance(node, ast.Compare):
        left = _eval_node(node.left, context, source)
        for operator, comparator in zip(node.ops, node.comparators):
            handler = _COMPARE_OPS.get(type(operator))
            if handler is None:
                raise BindingError(f"表达式 `{source}`: 不允许的比较符 {type(operator).__name__}")
            right = _eval_node(comparator, context, source)
            if not handler(left, right):
                return False
            left = right
        return True
    if isinstance(node, ast.IfExp):
        if _eval_node(node.test, context, source):
            return _eval_node(node.body, context, source)
        return _eval_node(node.orelse, context, source)
    if isinstance(node, ast.Call):
        if node.keywords:
            raise BindingError(f"表达式 `{source}`: 不支持关键字参数")
        if not isinstance(node.func, ast.Name):
            raise BindingError(f"表达式 `{source}`: 只允许调用白名单函数名")
        handler = ALLOWED_FUNCTIONS.get(node.func.id)
        if handler is None:
            raise BindingError(f"表达式 `{source}`: 函数 `{node.func.id}` 不在白名单内")
        return handler(*[_eval_node(arg, context, source) for arg in node.args])
    if isinstance(node, ast.List):
        return [_eval_node(item, context, source) for item in node.elts]
    if isinstance(node, ast.Tuple):
        return tuple(_eval_node(item, context, source) for item in node.elts)
    if isinstance(node, ast.Dict):
        return {
            _eval_node(key, context, source): _eval_node(value, context, source)
            for key, value in zip(node.keys, node.values)
            if key is not None
        }
    raise BindingError(f"表达式 `{source}` 使用了不允许的语法 {type(node).__name__}")


def parse_expression(source: str) -> ast.Expression:
    """Parse and statically screen an expression, without evaluating it."""
    text = str(source or "").strip()
    if not text:
        raise BindingError("表达式不能为空")
    try:
        tree = ast.parse(text, mode="eval")
    except SyntaxError as exc:
        raise BindingError(f"表达式 `{text}` 语法错误: {exc.msg}") from exc
    for node in ast.walk(tree):
        if not isinstance(node, _ALLOWED_NODES):
            raise BindingError(f"表达式 `{text}` 使用了不允许的语法 {type(node).__name__}")
        if isinstance(node, ast.Attribute) and node.attr.startswith("_"):
            raise BindingError(f"表达式 `{text}`: 不允许下划线开头的字段名")
        if isinstance(node, ast.Call):
            if not isinstance(node.func, ast.Name) or node.func.id not in ALLOWED_FUNCTIONS:
                raise BindingError(f"表达式 `{text}`: 只允许调用白名单函数")
    return tree


def evaluate(source: str, context: Mapping[str, Any]) -> Any:
    """Evaluate a restricted expression against ``context``."""
    tree = parse_expression(source)
    return _eval_node(tree, context, str(source).strip())


# --------------------------------------------------------------------------
# SQL literal rendering
# --------------------------------------------------------------------------

_SCALAR_TYPES = (str, int, float, bool, _dt.date, _dt.datetime)


def render_scalar(value: Any) -> str:
    """Render one scalar as a SQL literal it cannot escape from."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return repr(value)
    if isinstance(value, _dt.datetime):
        return "'" + value.isoformat(sep=" ") + "'"
    if isinstance(value, _dt.date):
        return "'" + value.isoformat() + "'"
    if isinstance(value, str):
        if "\x00" in value:
            raise BindingError("字符串字面量不允许包含 NUL 字符")
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"
    raise BindingError(f"不支持的字面量类型 {type(value).__name__}")


def render_checked_value(value: Any, *, expr: str) -> str:
    """Render a checked value: a scalar, or an array of scalars as a comma list."""
    if isinstance(value, _SCALAR_TYPES) or value is None:
        return render_scalar(value)
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        items = list(value)
        if not items:
            raise BindingError(f"受检值 `{expr}` 求值为空列表，无法展开为 SQL 列表；请改用谓词片段或条件分支")
        for item in items:
            if not (isinstance(item, _SCALAR_TYPES) or item is None):
                raise BindingError(f"受检值 `{expr}` 的数组元素必须是标量，实际含 {type(item).__name__}")
        return ", ".join(render_scalar(item) for item in items)
    raise BindingError(f"受检值 `{expr}` 必须求值为标量或标量数组，实际是 {type(value).__name__}")


def render_fragment(value: Any, *, expr: str, allowed_values: Sequence[str] = ()) -> str:
    """Render an unchecked fragment, restricted to a bare identifier or an enum value."""
    text = str(value).strip() if value is not None else ""
    if not text:
        raise BindingError(f"片段 `{expr}` 求值为空")
    if text in set(allowed_values):
        return text
    if not IDENTIFIER_RE.match(text):
        raise BindingError(
            f"片段 `{expr}` 的值 `{text}` 不是合法标识符；片段占位符只接受列名/别名或参数 values 枚举中的取值"
        )
    return text


# --------------------------------------------------------------------------
# Predicate DSL
# --------------------------------------------------------------------------

_LEAF_SQL_OPS = {
    "eq": "=",
    "ne": "<>",
    "lt": "<",
    "lte": "<=",
    "gt": ">",
    "gte": ">=",
    "like": "LIKE",
}

_COMPOSITE_OPS = {"and", "or", "not"}


def _resolve_predicate_value(raw: Any, context: Mapping[str, Any]) -> Any:
    """Resolve a predicate operand, which may be an expression reference."""
    if isinstance(raw, str) and raw.startswith("$"):
        return evaluate(raw[1:], context)
    return raw


def compile_predicate(predicate: Mapping[str, Any], context: Mapping[str, Any]) -> str:
    """Compile one predicate into a validated SQL fragment.

    Returns an empty string when the predicate resolves to nothing — that is the
    whole point of the DSL: an optional parameter that was not supplied makes its
    filter disappear instead of forcing the author to branch in the template.
    """
    op = str(predicate.get("op") or "").strip()
    if op in _COMPOSITE_OPS:
        clauses = predicate.get("clauses") or []
        rendered = [compile_predicate(clause, context) for clause in clauses]
        rendered = [item for item in rendered if item]
        if not rendered:
            return ""
        if op == "not":
            if len(rendered) != 1:
                raise BindingError("not 谓词必须恰好有一个非空子句")
            return f"NOT ({rendered[0]})"
        joiner = " AND " if op == "and" else " OR "
        if len(rendered) == 1:
            return rendered[0]
        return "(" + joiner.join(rendered) + ")"

    field = str(predicate.get("field") or "").strip()
    if not field:
        raise BindingError(f"谓词 `{op}` 缺少 field")
    column = render_fragment(field, expr=f"predicate.{op}.field")

    if op == "in":
        values = predicate.get("values")
        resolved = _resolve_predicate_value(values, context) if isinstance(values, str) else [
            _resolve_predicate_value(item, context) for item in (values or [])
        ]
        if resolved is None:
            return ""
        if not isinstance(resolved, Sequence) or isinstance(resolved, (str, bytes)):
            raise BindingError("in 谓词的 values 必须求值为列表")
        items = [item for item in resolved if item is not None]
        if not items:
            return ""
        return f"{column} IN ({', '.join(render_scalar(item) for item in items)})"

    if op == "between":
        values = predicate.get("values") or []
        if len(values) != 2:
            raise BindingError("between 谓词的 values 必须是两个元素")
        low = _resolve_predicate_value(values[0], context)
        high = _resolve_predicate_value(values[1], context)
        if low is None or high is None:
            return ""
        return f"{column} BETWEEN {render_scalar(low)} AND {render_scalar(high)}"

    sql_op = _LEAF_SQL_OPS.get(op)
    if sql_op is None:
        raise BindingError(f"未知的谓词运算符 `{op}`")
    value = _resolve_predicate_value(predicate.get("value"), context)
    if value is None:
        return ""
    return f"{column} {sql_op} {render_scalar(value)}"


def compile_predicate_clause(predicate: Mapping[str, Any], context: Mapping[str, Any], *, prefix: str = "AND") -> str:
    """Compile a predicate and prefix it with a connector when non-empty."""
    fragment = compile_predicate(predicate, context)
    if not fragment:
        return ""
    return f"{prefix} {fragment}" if prefix else fragment


# --------------------------------------------------------------------------
# Template binding
# --------------------------------------------------------------------------

def assert_template_markers_understood(template: str) -> None:
    """Reject a template carrying a brace marker no placeholder form consumed."""
    residue = PLACEHOLDER_RE.sub("", template)
    if "{{" in residue or "}}" in residue:
        raise BindingError("SQL 模板存在无法识别的 `{{`/`}}` 占位符形式")


def bind_sql(
    template: str,
    context: Mapping[str, Any],
    *,
    predicates: Mapping[str, Mapping[str, Any]] | None = None,
    fragment_allowed_values: Sequence[str] = (),
) -> str:
    """Bind a SQL template's three placeholder forms against ``context``."""
    predicates = predicates or {}
    assert_template_markers_understood(template)

    def _substitute(match: "re.Match[str]") -> str:
        marker = match.group("marker")
        body = match.group("body")
        if marker == "?":
            if not PREDICATE_NAME_RE.match(body):
                raise BindingError(f"谓词片段名 `{body}` 不合法")
            if body not in predicates:
                raise BindingError(f"谓词片段 `{body}` 未在本节点 predicates 中声明")
            return compile_predicate_clause(predicates[body], context)
        if marker == "!":
            return render_fragment(
                evaluate(body, context), expr=body, allowed_values=fragment_allowed_values
            )
        return render_checked_value(evaluate(body, context), expr=body)

    bound = PLACEHOLDER_RE.sub(_substitute, template)
    # An emptied predicate leaves a dangling run of blanks; only that is collapsed,
    # and never inside a string literal, so trailing whitespace per line is enough.
    return "\n".join(line.rstrip() for line in bound.splitlines()).strip()


def iter_template_expressions(template: str) -> List[str]:
    """List every expression referenced by a template, for static validation."""
    return [
        match.group("body")
        for match in PLACEHOLDER_RE.finditer(template)
        if match.group("marker") != "?"
    ]


def iter_template_predicates(template: str) -> List[str]:
    return [
        match.group("body")
        for match in PLACEHOLDER_RE.finditer(template)
        if match.group("marker") == "?"
    ]
