#!/usr/bin/env python3
"""Demand-driven, memoized, pruned, parallel evaluation of a methodology DAG.

Evaluation is keyed on the target. Each node maps to a lazily-created, memoized
holder of its result; asking for the target forces its holder, which forces its
dependencies transitively. Two properties follow, and they are the whole point:

* **Only what is needed runs.** A node whose holder is never forced never
  executes — including the untaken side of a conditional and everything
  reachable only through it.
* **It runs at most once.** The holder is memoized behind a double-checked lock,
  so a dependency shared by several paths is computed a single time.

Independent dependencies are forced concurrently, so the author writes no
concurrency code: the parallelism is a consequence of the declared dependency
structure.
"""

from __future__ import annotations

import json
import os
import sqlite3
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Mapping, Optional, Sequence

from binding import BindingError, bind_sql, evaluate

DEFAULT_TOTAL_TIMEOUT_SECONDS = 240
DEFAULT_NODE_TIMEOUT_SECONDS = 60
MAX_PARALLEL_DEPENDENCIES = 8


class MethodologyError(RuntimeError):
    """A failure carrying the same attribution shape the SQL tool path uses."""

    def __init__(
        self,
        message: str,
        *,
        error_code: str,
        failure_attribution: Sequence[str] = (),
        stop_reason: str = "",
        retryable: bool = False,
        node: str | None = None,
    ) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.failure_attribution = list(failure_attribution)
        self.stop_reason = stop_reason or message
        self.retryable = retryable
        self.node = node


@dataclass
class NodeResult:
    """One node's value: a table plus the provenance the caller may report."""

    columns: List[str] = field(default_factory=list)
    rows: List[Dict[str, Any]] = field(default_factory=list)
    sql: Optional[str] = None
    database: Optional[str] = None
    engine: Optional[str] = None
    notice: Optional[str] = None
    has_more: bool = False
    truncated_by_size: bool = False

    def as_context(self) -> Dict[str, Any]:
        return {"columns": list(self.columns), "rows": list(self.rows), "row_count": len(self.rows)}


class _Lazy:
    """Double-checked, lock-guarded holder so the supplier runs exactly once."""

    __slots__ = ("_supplier", "_lock", "_done", "_value", "_error")

    def __init__(self, supplier: Callable[[], Any]) -> None:
        self._supplier = supplier
        self._lock = threading.Lock()
        self._done = False
        self._value: Any = None
        self._error: BaseException | None = None

    def get(self) -> Any:
        if not self._done:
            with self._lock:
                if not self._done:
                    try:
                        self._value = self._supplier()
                    except BaseException as exc:  # noqa: BLE001 - re-raised below
                        self._error = exc
                    finally:
                        self._done = True
        if self._error is not None:
            raise self._error
        return self._value


def _columns_of(rows: Sequence[Mapping[str, Any]], declared: Sequence[str] = ()) -> List[str]:
    if declared:
        return list(declared)
    columns: List[str] = []
    for row in rows:
        for key in row:
            if key not in columns:
                columns.append(key)
    return columns


def _resolve_python_bin() -> str:
    return str(os.getenv("DATAAGENT_PYTHON_BIN") or "").strip() or sys.executable


PLATFORM_TOOLS_FOLDER = "opendataworks-platform-tools"


def _platform_skill_root() -> Path:
    """同级 platform-tools 技能目录，不校验是否存在。

    只走同级目录：技能并排安装，本技能从自身位置就能找到邻居，不需要宿主注入
    任何东西。此前这里返回一个候选列表，第一个候选来自
    DATAAGENT_PLATFORM_SKILL_ROOT 覆盖；该变量已随调用锚点统一移除——它恒等于
    ${SKILLS_ROOT_DIR}/opendataworks-platform-tools，与同级目录解析结果相同。

    调用方各自需要的脚本不同（run_sql.py / validate_sql.py），存在性由调用方检查。
    """
    skill_root = Path(__file__).resolve().parent.parent
    return (skill_root.parent / PLATFORM_TOOLS_FOLDER).resolve(strict=False)


def _resolve_platform_skill_root() -> Path:
    root = _platform_skill_root()
    if (root / "scripts" / "run_sql.py").is_file():
        return root
    raise MethodologyError(
        "找不到 opendataworks-platform-tools：方法论的 sql 节点依赖它执行只读查询。已尝试："
        + str(root),
        error_code="platform_tools_unavailable",
        failure_attribution=["invalid_tool_path"],
        stop_reason=(
            "本 skill 必须与 opendataworks-platform-tools 一起安装并启用；"
            "请先启用平台工具技能，不要改用其他 SQL 执行方式。"
        ),
    )


class MethodologyEngine:
    """Evaluates one methodology per instance; state is request-scoped."""

    def __init__(
        self,
        methodology: Mapping[str, Any],
        params: Mapping[str, Any],
        *,
        registry: Mapping[str, Mapping[str, Any]] | None = None,
        mock: Mapping[str, Any] | None = None,
        total_timeout: float = DEFAULT_TOTAL_TIMEOUT_SECONDS,
        node_timeout: float = DEFAULT_NODE_TIMEOUT_SECONDS,
        query_limit: int = 1000,
        deadline: float | None = None,
        call_chain: Sequence[str] = (),
        trace: List[Dict[str, Any]] | None = None,
    ) -> None:
        self.methodology = methodology
        self.params = dict(params)
        self.registry = dict(registry or {})
        self.mock = dict(mock or {})
        self.node_timeout = float(node_timeout)
        self.query_limit = int(query_limit)
        self.deadline = deadline if deadline is not None else time.monotonic() + float(total_timeout)
        self.call_chain = list(call_chain) or [str(methodology.get("id") or "")]
        self.trace: List[Dict[str, Any]] = trace if trace is not None else []

        self.nodes: Dict[str, Mapping[str, Any]] = dict(methodology.get("nodes") or {})
        self.target = str(methodology.get("target") or "")
        self._lazies: Dict[str, _Lazy] = {}
        self._cache_lock = threading.Lock()
        self._trace_lock = threading.Lock()
        self._fragment_values = self._collect_fragment_values(methodology)

    # -- public ------------------------------------------------------------

    def run(self) -> NodeResult:
        if self.target not in self.nodes:
            raise MethodologyError(
                f"target 节点 `{self.target}` 不存在",
                error_code="methodology_invalid",
                failure_attribution=["invalid_methodology"],
            )
        return self._force(self.target)

    # -- evaluation core ---------------------------------------------------

    def _force(self, name: str) -> NodeResult:
        """Return the node's memoized result, running it at most once."""
        lazy = self._lazies.get(name)
        if lazy is None:
            with self._cache_lock:
                lazy = self._lazies.get(name)
                if lazy is None:
                    lazy = _Lazy(lambda node_name=name: self._evaluate(node_name))
                    self._lazies[name] = lazy
        return lazy.get()

    def _force_dependencies(self, names: Sequence[str]) -> Dict[str, NodeResult]:
        """Force independent dependencies together; a shared one still runs once."""
        unique: List[str] = []
        for name in names:
            if name not in unique:
                unique.append(name)
        if len(unique) <= 1:
            return {name: self._force(name) for name in unique}
        # A pool per resolution site keeps nested forcing from starving a shared,
        # fixed-size pool. Graphs are small, so the thread cost is negligible.
        workers = min(len(unique), MAX_PARALLEL_DEPENDENCIES)
        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="qdag") as pool:
            futures = {name: pool.submit(self._force, name) for name in unique}
            return {name: future.result() for name, future in futures.items()}

    def _evaluate(self, name: str) -> NodeResult:
        node = self.nodes.get(name)
        if node is None:
            raise MethodologyError(
                f"节点 `{name}` 不存在",
                error_code="methodology_invalid",
                failure_attribution=["invalid_methodology"],
                node=name,
            )
        self._check_deadline(name)
        node_type = str(node.get("type") or "")
        started = time.monotonic()

        if name in self.mock:
            result = self._mock_result(name)
            self._record(name, node_type, "mock", started, result)
            return result

        if node_type == "conditional":
            return self._evaluate_conditional(name, node, started)

        dependencies = self._force_dependencies(list(node.get("dependencies") or []))
        context = self._context(dependencies)

        handlers: Dict[str, Callable[[str, Mapping[str, Any], Mapping[str, Any], Dict[str, NodeResult]], NodeResult]] = {
            "sql": self._run_sql,
            "sqlite": self._run_sqlite,
            "transform": self._run_transform,
            "literal": self._run_literal,
            "call": self._run_call,
        }
        handler = handlers.get(node_type)
        if handler is None:
            raise MethodologyError(
                f"节点 `{name}` 的类型 `{node_type}` 不受支持",
                error_code="methodology_invalid",
                failure_attribution=["invalid_methodology"],
                node=name,
            )
        result = handler(name, node, context, dependencies)
        self._record(name, node_type, "success", started, result)
        return result

    def _evaluate_conditional(self, name: str, node: Mapping[str, Any], started: float) -> NodeResult:
        """Resolve the predicate first, then force only the selected branch.

        The branches are soft dependencies: the untaken one — and the entire
        sub-graph reachable only through it — is never forced.
        """
        dependencies = self._force_dependencies(list(node.get("dependencies") or []))
        context = self._context(dependencies)
        expression = str(node.get("when") or "")
        try:
            taken = bool(evaluate(expression, context))
        except BindingError as exc:
            raise MethodologyError(
                f"节点 `{name}` 的条件表达式求值失败: {exc}",
                error_code="methodology_invalid",
                failure_attribution=["invalid_methodology"],
                node=name,
            ) from exc
        chosen = str(node.get("then_node") if taken else node.get("else_node"))
        skipped = str(node.get("else_node") if taken else node.get("then_node"))
        result = self._force(chosen)
        self._record(name, "conditional", "success", started, result, extra={"branch": chosen, "pruned_branch": skipped})
        return result

    # -- node executors ----------------------------------------------------

    def _run_sql(
        self,
        name: str,
        node: Mapping[str, Any],
        context: Mapping[str, Any],
        _dependencies: Dict[str, NodeResult],
    ) -> NodeResult:
        bound_sql = self._bind(name, node, context)
        database = str(node.get("database") or "").strip()
        engine = str(node.get("engine") or "").strip()
        limit = int(node.get("limit") or self.query_limit)

        platform_root = _resolve_platform_skill_root()
        command = [
            _resolve_python_bin(),
            str(platform_root / "scripts" / "run_sql.py"),
            "--database",
            database,
            "--sql",
            bound_sql,
            "--limit",
            str(limit),
        ]
        if engine:
            command.extend(["--engine", engine])

        timeout = self._node_budget(name)
        try:
            completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
                command,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise MethodologyError(
                f"节点 `{name}` 执行只读查询超时（{timeout:.0f}s）",
                error_code="tool_timeout",
                failure_attribution=["tool_timeout"],
                stop_reason="方法论中的查询节点超时，已停止重复执行；需要缩小时间范围或增加过滤条件。",
                node=name,
            ) from exc

        payload = self._parse_tool_json(name, completed)
        state = str(payload.get("result_state") or "")
        if state == "failed":
            raise MethodologyError(
                str(payload.get("error") or payload.get("stop_reason") or f"节点 `{name}` 查询失败"),
                error_code=str(payload.get("error_code") or "query_failed"),
                failure_attribution=list(payload.get("failure_attribution") or ["tool_error"]),
                stop_reason=str(payload.get("stop_reason") or ""),
                retryable=bool(payload.get("retryable")),
                node=name,
            )
        rows = list(payload.get("rows") or [])
        return NodeResult(
            columns=_columns_of(rows, payload.get("columns") or []),
            rows=rows,
            sql=bound_sql,
            database=str(payload.get("database") or database) or None,
            engine=str(payload.get("engine") or engine) or None,
            notice=payload.get("notice"),
            has_more=bool(payload.get("has_more")),
            truncated_by_size=bool(payload.get("truncated_by_size")),
        )

    def _run_sqlite(
        self,
        name: str,
        node: Mapping[str, Any],
        context: Mapping[str, Any],
        dependencies: Dict[str, NodeResult],
    ) -> NodeResult:
        bound_sql = self._bind(name, node, context)
        primary_keys = dict(node.get("primary_keys") or {})
        connection = sqlite3.connect(":memory:")
        try:
            connection.row_factory = sqlite3.Row
            for dep_name, dep_result in dependencies.items():
                self._materialize(connection, dep_name, dep_result, primary_keys.get(dep_name) or [])
            try:
                cursor = connection.execute(bound_sql)
                fetched = cursor.fetchall()
            except sqlite3.Error as exc:
                raise MethodologyError(
                    f"节点 `{name}` 的内存 SQL 执行失败: {exc}",
                    error_code="query_failed",
                    failure_attribution=["invalid_sql"],
                    stop_reason="方法论内存计算节点的 SQL 有误，需要修正方法论定义而不是重试。",
                    node=name,
                ) from exc
            columns = [description[0] for description in (cursor.description or [])]
            rows = [{column: row[column] for column in columns} for row in fetched]
        finally:
            connection.close()
        return NodeResult(columns=columns, rows=rows, sql=bound_sql)

    def _run_transform(
        self,
        name: str,
        node: Mapping[str, Any],
        context: Mapping[str, Any],
        dependencies: Dict[str, NodeResult],
    ) -> NodeResult:
        source_name = list(node.get("dependencies") or [])[0]
        source = dependencies[source_name]
        filter_expr = node.get("filter")
        derive = dict(node.get("derive") or {})
        rename = dict(node.get("rename") or {})
        select = list(node.get("select") or [])

        produced: List[Dict[str, Any]] = []
        for row in source.rows:
            row_context = dict(context)
            row_context["row"] = row
            try:
                if filter_expr and not evaluate(str(filter_expr), row_context):
                    continue
                current = dict(row)
                for column, expression in derive.items():
                    row_context["row"] = current
                    current[column] = evaluate(str(expression), row_context)
            except BindingError as exc:
                raise MethodologyError(
                    f"节点 `{name}` 的变换表达式求值失败: {exc}",
                    error_code="methodology_invalid",
                    failure_attribution=["invalid_methodology"],
                    node=name,
                ) from exc
            for old, new in rename.items():
                if old in current:
                    current[new] = current.pop(old)
            if select:
                current = {column: current.get(column) for column in select}
            produced.append(current)

        for spec in reversed(list(node.get("sort") or [])):
            field_name = str(spec.get("field"))
            reverse = str(spec.get("order") or "asc") == "desc"
            produced.sort(key=lambda item, key=field_name: (item.get(key) is None, item.get(key)), reverse=reverse)

        limit = node.get("limit")
        if limit:
            produced = produced[: int(limit)]

        columns = select or _columns_of(produced, [])
        return NodeResult(columns=columns, rows=produced)

    def _run_literal(
        self,
        _name: str,
        node: Mapping[str, Any],
        _context: Mapping[str, Any],
        _dependencies: Dict[str, NodeResult],
    ) -> NodeResult:
        rows = [dict(row) for row in (node.get("rows") or [])]
        return NodeResult(columns=_columns_of(rows, node.get("columns") or []), rows=rows)

    def _run_call(
        self,
        name: str,
        node: Mapping[str, Any],
        context: Mapping[str, Any],
        _dependencies: Dict[str, NodeResult],
    ) -> NodeResult:
        target_id = str(node.get("methodology_id") or "")
        if target_id in self.call_chain:
            chain = " -> ".join([*self.call_chain, target_id])
            raise MethodologyError(
                f"方法论调用成环: {chain}",
                error_code="methodology_invalid",
                failure_attribution=["invalid_methodology"],
                stop_reason="方法论调用图必须无环；请修正 call 节点的 methodology_id。",
                node=name,
            )
        target = self.registry.get(target_id)
        if target is None:
            raise MethodologyError(
                f"节点 `{name}` 调用的方法论 `{target_id}` 不在注册表中",
                error_code="methodology_not_found",
                failure_attribution=["invalid_methodology"],
                node=name,
            )
        try:
            child_params = {
                key: evaluate(str(expression), context) for key, expression in (node.get("params") or {}).items()
            }
        except BindingError as exc:
            raise MethodologyError(
                f"节点 `{name}` 的调用参数求值失败: {exc}",
                error_code="methodology_invalid",
                failure_attribution=["invalid_methodology"],
                node=name,
            ) from exc
        child = MethodologyEngine(
            target,
            child_params,
            registry=self.registry,
            mock=self.mock,
            node_timeout=self.node_timeout,
            query_limit=self.query_limit,
            deadline=self.deadline,
            call_chain=[*self.call_chain, target_id],
            trace=self.trace,
        )
        return child.run()

    # -- helpers -----------------------------------------------------------

    def _bind(self, name: str, node: Mapping[str, Any], context: Mapping[str, Any]) -> str:
        try:
            return bind_sql(
                str(node.get("sql") or ""),
                context,
                predicates=dict(node.get("predicates") or {}),
                fragment_allowed_values=self._fragment_values,
            )
        except BindingError as exc:
            raise MethodologyError(
                f"节点 `{name}` 的 SQL 模板绑定失败: {exc}",
                error_code="param_rejected",
                failure_attribution=["invalid_methodology"],
                stop_reason="方法论参数绑定被拒绝，属于口径或参数问题，不要改写 SQL 重试。",
                node=name,
            ) from exc

    def _context(self, dependencies: Mapping[str, NodeResult]) -> Dict[str, Any]:
        context: Dict[str, Any] = {"params": dict(self.params)}
        for name, result in dependencies.items():
            context[name] = result.as_context()
        return context

    @staticmethod
    def _collect_fragment_values(methodology: Mapping[str, Any]) -> List[str]:
        allowed: List[str] = []
        for spec in methodology.get("params") or []:
            allowed.extend(str(value) for value in (spec.get("values") or []))
        return allowed

    def _mock_result(self, name: str) -> NodeResult:
        supplied = self.mock[name]
        payload = supplied() if callable(supplied) else supplied
        if isinstance(payload, NodeResult):
            return payload
        rows = [dict(row) for row in (payload.get("rows") or [])]
        return NodeResult(
            columns=_columns_of(rows, payload.get("columns") or []),
            rows=rows,
            sql=payload.get("sql"),
        )

    def _materialize(
        self,
        connection: sqlite3.Connection,
        table: str,
        result: NodeResult,
        primary_keys: Sequence[str],
    ) -> None:
        columns = result.columns or _columns_of(result.rows)
        if not columns:
            connection.execute(f'CREATE TABLE "{table}" ("_empty" TEXT)')
            return
        definitions = ", ".join(f'"{column}"' for column in columns)
        constraint = ""
        if primary_keys:
            constraint = ", PRIMARY KEY (" + ", ".join(f'"{key}"' for key in primary_keys) + ")"
        connection.execute(f'CREATE TABLE "{table}" ({definitions}{constraint})')
        placeholders = ", ".join("?" for _ in columns)
        connection.executemany(
            f'INSERT INTO "{table}" ({definitions}) VALUES ({placeholders})',
            [tuple(_sqlite_value(row.get(column)) for column in columns) for row in result.rows],
        )

    def _node_budget(self, name: str) -> float:
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise MethodologyError(
                f"方法论总执行预算已耗尽（节点 `{name}` 尚未开始）",
                error_code="methodology_timeout",
                failure_attribution=["tool_timeout"],
                stop_reason="方法论总执行时间超出预算；请缩小参数范围或改用后台执行。",
                node=name,
            )
        return min(self.node_timeout, remaining)

    def _check_deadline(self, name: str) -> None:
        if time.monotonic() >= self.deadline:
            raise MethodologyError(
                f"方法论总执行预算已耗尽（节点 `{name}` 尚未开始）",
                error_code="methodology_timeout",
                failure_attribution=["tool_timeout"],
                stop_reason="方法论总执行时间超出预算；请缩小参数范围或改用后台执行。",
                node=name,
            )

    def _parse_tool_json(self, name: str, completed: subprocess.CompletedProcess) -> Dict[str, Any]:
        text = (completed.stdout or "").strip()
        if not text:
            raise MethodologyError(
                f"节点 `{name}` 的查询工具没有返回内容: {(completed.stderr or '').strip()[:400]}",
                error_code="query_failed",
                failure_attribution=["tool_error"],
                node=name,
            )
        try:
            return json.loads(text)
        except json.JSONDecodeError as exc:
            raise MethodologyError(
                f"节点 `{name}` 的查询工具返回了非 JSON 内容: {text[:400]}",
                error_code="query_failed",
                failure_attribution=["tool_error"],
                node=name,
            ) from exc

    def _record(
        self,
        name: str,
        node_type: str,
        status: str,
        started: float,
        result: NodeResult,
        *,
        extra: Mapping[str, Any] | None = None,
    ) -> None:
        entry: Dict[str, Any] = {
            "name": name,
            "type": node_type,
            "status": status,
            "duration_ms": int((time.monotonic() - started) * 1000),
            "row_count": len(result.rows),
        }
        if extra:
            entry.update(extra)
        with self._trace_lock:
            self.trace.append(entry)


def _sqlite_value(value: Any) -> Any:
    if isinstance(value, bool):
        return int(value)
    if value is None or isinstance(value, (str, int, float, bytes)):
        return value
    return json.dumps(value, ensure_ascii=False, default=str)


def run_methodology(
    methodology: Mapping[str, Any],
    params: Mapping[str, Any],
    **kwargs: Any,
) -> tuple[NodeResult, List[Dict[str, Any]]]:
    """Convenience wrapper returning the target result plus the per-node trace."""
    engine = MethodologyEngine(methodology, params, **kwargs)
    return engine.run(), engine.trace
