# DataAgent 系统提示落库设计

日期：2026-10-22。涉及栈：`dataagent/dataagent-backend`（Python、Alembic、MySQL `dataagent` schema）。实施计划见[对应计划](../plans/2026-10-22-dataagent-system-prompt-persistence-plan.md)。

## 现状与问题

每次任务执行时，`core/task_executor.py` 用 `_build_system_prompt()` 现拼系统提示，由以下几部分组成：

- 模板文件 `prompts/data_agent_system_prompt.md`（约 9KB，有 24 次提交）；
- 运行时上下文：已启用的 skills、可写临时目录；
- 智能体自定义提示词；
- 授权数据范围。

`da_agent_task.agent_snapshot_json` 只保存了智能体配置，没有保存最终发给模型的系统提示。模板文件或运行时配置一旦变化，就无法还原某次任务里模型实际看到了什么，排查问题和复现评测时对不上。Pi 和 Claude 两个引擎都有这个问题，与 Pi 升级无关。

## 方案

```text
da_system_prompt
  prompt_sha256  CHAR(64) PK      内容的 sha256，相同内容只存一份
  content        MEDIUMTEXT
  byte_length    INT
  created_at     DATETIME

da_agent_task
  + system_prompt_sha256 CHAR(64) NULL
```

- 写入时机：`_execute_task_stream_local` 拼好系统提示后、分派给 Pi 或 Claude 引擎之前。本地进程和沙箱子容器都走这条路径，并且都已直连 `dataagent` 库写 `da_agent_sdk_record`，所以两种部署方式都能写入，不需要经过 runner 协议转发。
- 写入方式：`INSERT IGNORE` 写入 `da_system_prompt`，再 `UPDATE da_agent_task` 记录 hash。同一个任务恢复执行时，如果系统提示变化，记录会更新为最后一次执行时使用的提示。
- 写入前先锁定并确认任务行存在；任务行不存在时整笔回滚、记 warning，不留下孤儿记录。
- 内容超过 `MEDIUMTEXT` 上限（16MB）时直接拒绝：`INSERT IGNORE` 会把截断降级为 warning，导致 hash 与存储的正文不一致。
- 写库放到线程中执行，连接、读、写超时都设为 5 秒，不阻塞驱动心跳和取消的事件循环。
- 写入失败只记 warning，不影响任务执行。这是辅助排障和复现的数据，属于尽力记录，不能当作完整审计。
- 读取：管理员接口 `GET /api/v1/dataagent/tasks/{task_id}/system-prompt`，返回 `{task_id, prompt_sha256, byte_length, created_at, content}`；任务不存在或没有记录时返回 404。认证开启时匿名请求返回 401、普通用户返回 403；认证关闭时沿用全局的放行策略。普通用户的会话接口和事件流不返回这部分内容，因为里面有内部指令和授权数据范围。

## 不做的事

- 不写入 `da_agent_sdk_record`：这张表用于重建前端展示的消息块，加入 system 记录会让所有读取端都要额外过滤，还可能把内部指令暴露给普通用户。
- 不按轮次存：一次任务内的系统提示不变。
- 本期不做前端页面，只提供接口；管理端的查看入口单独排期。

## 清理

删除会话时会删掉 `da_agent_task` 的行，但 `da_system_prompt` 会留下不再被引用的记录。最终提示里包含用户提供的 `database_hint`，不同的 hint 会生成不同的记录，所以记录数并不只随模板和配置的变更增长。本期先不做清理；如果增长明显，再加一个按"无任务引用且超过 N 天"删除的定时清理。
