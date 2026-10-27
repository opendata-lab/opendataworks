#!/usr/bin/env python3
"""Pydantic data model and JSON Schema for methodology DAG artifacts.

A methodology is a declarative directed acyclic graph of typed steps. Each node
consumes and produces a single table-shaped value; exactly one node is the
``target``. The engine — not the author — decides what runs, in what order,
what runs in parallel, what is skipped and what is reused.

Run as a script to print the JSON Schema:

    python3 methodology_schema.py > ../assets/methodology.schema.json
"""

from __future__ import annotations

import json
from typing import Annotated, Any, Dict, List, Literal, Optional, Union

from pydantic import BaseModel, ConfigDict, Field


SnakeCaseId = str

SNAKE_CASE_PATTERN = r"^[a-z][a-z0-9_]*$"
SEMVER_PATTERN = r"^\d+\.\d+\.\d+$"

NODE_TYPE_OPTIONS: Dict[str, Dict[str, str]] = {
    "sql": {
        "group": "query",
        "label_zh": "只读查询",
        "description": "执行模板化只读 SQL，通过 MCP odw_query_readonly 执行",
    },
    "sqlite": {
        "group": "compute",
        "label_zh": "内存关系计算",
        "description": "把依赖节点结果装入内存 SQLite 后执行完整 SQL，支持跨引擎 join 与集合运算",
    },
    "transform": {
        "group": "compute",
        "label_zh": "表变换",
        "description": "对单个依赖结果做行级过滤、派生列、改名、排序和截断",
    },
    "literal": {
        "group": "compute",
        "label_zh": "常量表",
        "description": "固定的行集合，例如维度到列名的映射表",
    },
    "conditional": {
        "group": "control",
        "label_zh": "条件分支",
        "description": "先求谓词再解析二选一分支；未选中分支及其子图不执行",
    },
    "call": {
        "group": "control",
        "label_zh": "调用方法论",
        "description": "调用注册表中的另一个方法论，是复用单元；调用链禁止成环",
    },
}

PARAM_TYPE_OPTIONS: Dict[str, Dict[str, str]] = {
    "string": {"label_zh": "字符串"},
    "int": {"label_zh": "整数"},
    "float": {"label_zh": "浮点数"},
    "bool": {"label_zh": "布尔"},
    "enum": {"label_zh": "枚举", "description": "取值必须命中 values"},
    "date": {"label_zh": "日期", "description": "YYYY-MM-DD"},
    "string_list": {"label_zh": "字符串列表", "description": "用于 IN 列表"},
}

PREDICATE_OP_OPTIONS: Dict[str, Dict[str, str]] = {
    "eq": {"label_zh": "等于", "arity": "field+value"},
    "ne": {"label_zh": "不等于", "arity": "field+value"},
    "lt": {"label_zh": "小于", "arity": "field+value"},
    "lte": {"label_zh": "小于等于", "arity": "field+value"},
    "gt": {"label_zh": "大于", "arity": "field+value"},
    "gte": {"label_zh": "大于等于", "arity": "field+value"},
    "like": {"label_zh": "模糊匹配", "arity": "field+value"},
    "in": {"label_zh": "属于", "arity": "field+values"},
    "between": {"label_zh": "区间", "arity": "field+values(2)"},
    "and": {"label_zh": "与", "arity": "clauses"},
    "or": {"label_zh": "或", "arity": "clauses"},
    "not": {"label_zh": "非", "arity": "clauses(1)"},
}

SORT_ORDER_OPTIONS: Dict[str, Dict[str, str]] = {
    "asc": {"label_zh": "升序"},
    "desc": {"label_zh": "降序"},
}

ENGINE_OPTIONS: Dict[str, Dict[str, str]] = {
    "mysql": {"label_zh": "MySQL"},
    "doris": {"label_zh": "Doris"},
}

PLACEHOLDER_FORMS: Dict[str, Dict[str, str]] = {
    "{{ expr }}": {
        "label_zh": "受检值",
        "description": "求值结果必须是标量或标量数组；标量转义加引号，数组展开为逗号列表。值无法逃出字面量位置。",
    },
    "{{! expr }}": {
        "label_zh": "标识符片段",
        "description": "只允许标识符（列名、表别名）。必须匹配 ^[A-Za-z_][A-Za-z0-9_.]*$ 或命中参数 values 枚举。",
    },
    "{{? name }}": {
        "label_zh": "谓词片段",
        "description": "引用本节点 predicates 里的同名谓词；null 子谓词被丢弃，全空时渲染为空串。",
    },
}

FIELD_DICTIONARY: Dict[str, Any] = {
    "top_level_fields": {
        "title": "顶层字段",
        "values": {
            "id": {"type": "string", "required": "是", "description": "snake_case 方法论标识，注册表内唯一"},
            "version": {"type": "string", "required": "是", "description": "语义化版本；口径变更必须升版本"},
            "name_zh": {"type": "string", "required": "是", "description": "中文名"},
            "intent": {"type": "string", "required": "是", "description": "本方法论回答什么问题"},
            "caliber": {"type": "string", "required": "是", "description": "统计口径：时间字段、区间、过滤与排除规则"},
            "owner": {"type": "string", "required": "是", "description": "口径责任人或团队"},
            "synonyms": {"type": "string[]", "required": "否", "description": "检索用同义词"},
            "ontology_ref": {"type": "object", "required": "否", "description": "关联的本体 query_function"},
            "output_fields": {"type": "string[]", "required": "否", "description": "target 节点预期输出列"},
            "params": {"type": "object[]", "required": "否", "description": "参数槽位声明"},
            "nodes": {"type": "object", "required": "是", "description": "节点名到节点定义的映射"},
            "target": {"type": "string", "required": "是", "description": "目标节点名；执行只走到它的传递依赖为止"},
        },
    },
    "nodes.type": {"title": "nodes.type", "values": NODE_TYPE_OPTIONS},
    "params.type": {"title": "params.type", "values": PARAM_TYPE_OPTIONS},
    "predicates.op": {"title": "predicates.op", "values": PREDICATE_OP_OPTIONS},
    "sort.order": {"title": "sort.order", "values": SORT_ORDER_OPTIONS},
    "sql.engine": {"title": "sql.engine", "values": ENGINE_OPTIONS},
    "placeholder_forms": {"title": "SQL 模板占位符", "values": PLACEHOLDER_FORMS},
}

NodeType = Literal[*tuple(NODE_TYPE_OPTIONS)]
ParamType = Literal[*tuple(PARAM_TYPE_OPTIONS)]
PredicateOp = Literal[*tuple(PREDICATE_OP_OPTIONS)]
SortOrder = Literal[*tuple(SORT_ORDER_OPTIONS)]
Engine = Literal[*tuple(ENGINE_OPTIONS)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class OntologyRef(StrictModel):
    skill: str
    function_name: SnakeCaseId = Field(pattern=SNAKE_CASE_PATTERN)


class ParamSpec(StrictModel):
    name: SnakeCaseId = Field(pattern=SNAKE_CASE_PATTERN)
    type: ParamType
    description: str
    required: bool = False
    default: Any = None
    values: List[str] = Field(default_factory=list)
    minimum: Optional[float] = None
    maximum: Optional[float] = None
    max_items: Optional[int] = None


class Predicate(StrictModel):
    """One node of the predicate DSL.

    Leaf predicates carry ``field`` plus ``value``/``values``; a leaf whose bound
    value resolves to null is dropped at compile time, so an absent optional
    parameter simply makes its filter disappear. Composite predicates
    (``and``/``or``/``not``) carry ``clauses``.
    """

    op: PredicateOp
    field: Optional[str] = None
    value: Any = None
    # A ``$expr`` string resolves to a list at bind time (e.g. an IN list taken
    # from an upstream node); a literal list is used as-is.
    values: Union[str, List[Any], None] = None
    clauses: List["Predicate"] = Field(default_factory=list)


class SortSpec(StrictModel):
    field: str
    order: SortOrder = "asc"


class BaseNode(StrictModel):
    description: Optional[str] = None
    dependencies: List[str] = Field(default_factory=list)


class SqlNode(BaseNode):
    type: Literal["sql"]
    database: str
    sql: str
    engine: Optional[Engine] = None
    predicates: Dict[str, Predicate] = Field(default_factory=dict)
    limit: Optional[int] = Field(default=None, ge=1)


class SqliteNode(BaseNode):
    type: Literal["sqlite"]
    sql: str
    dependencies: List[str] = Field(min_length=1)
    primary_keys: Dict[str, List[str]] = Field(default_factory=dict)


class TransformNode(BaseNode):
    type: Literal["transform"]
    dependencies: List[str] = Field(min_length=1, max_length=1)
    filter: Optional[str] = None
    derive: Dict[str, str] = Field(default_factory=dict)
    rename: Dict[str, str] = Field(default_factory=dict)
    select: List[str] = Field(default_factory=list)
    sort: List[SortSpec] = Field(default_factory=list)
    limit: Optional[int] = Field(default=None, ge=1)


class LiteralNode(BaseNode):
    type: Literal["literal"]
    rows: List[Dict[str, Any]] = Field(default_factory=list)
    columns: List[str] = Field(default_factory=list)


class ConditionalNode(BaseNode):
    type: Literal["conditional"]
    when: str
    then_node: str
    else_node: str


class CallNode(BaseNode):
    type: Literal["call"]
    methodology_id: SnakeCaseId = Field(pattern=SNAKE_CASE_PATTERN)
    params: Dict[str, str] = Field(default_factory=dict)


Node = Annotated[
    Union[SqlNode, SqliteNode, TransformNode, LiteralNode, ConditionalNode, CallNode],
    Field(discriminator="type"),
]


class Methodology(StrictModel):
    id: SnakeCaseId = Field(pattern=SNAKE_CASE_PATTERN)
    version: str = Field(pattern=SEMVER_PATTERN)
    name_zh: str
    intent: str
    caliber: str
    owner: str
    nodes: Dict[str, Node] = Field(min_length=1)
    target: str
    name_en: Optional[str] = None
    synonyms: List[str] = Field(default_factory=list)
    ontology_ref: Optional[OntologyRef] = None
    output_fields: List[str] = Field(default_factory=list)
    params: List[ParamSpec] = Field(default_factory=list)
    notes: Optional[str] = None


def methodology_json_schema() -> Dict[str, Any]:
    schema = Methodology.model_json_schema()
    schema["x-field-dictionary"] = FIELD_DICTIONARY
    return schema


def methodology_json_schema_text() -> str:
    return json.dumps(methodology_json_schema(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"


if __name__ == "__main__":
    print(methodology_json_schema_text(), end="")
