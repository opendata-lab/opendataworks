# Real-task delta replay fixture

`real-task-delta-replay.json.gz` is an unmodified projection-input snapshot from
local MySQL table `dataagent.da_agent_sdk_record`, task
`task_0a188428626c42a6a519fa8f`, captured on 2026-09-11 before the transient-delta
migration ran.

The gzip payload contains the source query, expected counts, and all 20,300 rows
in `(id, turn_index, record_type, event_type, data)` order. It includes 20,117
real `content.delta` records and 183 durable records. Its SHA-256 is:

`8d3078537c9c9a49afba89df06fe7a654e3962e65e1c8d58e0118d073a125418`

The fixture is compressed because the exact JSON is 3.7 MB while the deterministic
gzip is about 202 KB. The frontend regression test replays the full record set
through the production reducer, removes only `content.delta`, replays again, and
compares the complete block structures and text.

