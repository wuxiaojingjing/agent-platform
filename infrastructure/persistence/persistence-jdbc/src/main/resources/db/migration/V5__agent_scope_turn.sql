-- 架构草案阶段 1：对话轮次按 agent_id 分片。
--
-- 主键从 (session_id, seq) 扩为 (agent_id, session_id, seq)，避免多 Agent 共会话键时序号撞车。

alter table agent_conversation_turn
    add column if not exists agent_id varchar(64) not null default 'agent.entry';

alter table agent_conversation_turn
    drop constraint if exists agent_conversation_turn_pkey;

alter table agent_conversation_turn
    add primary key (agent_id, session_id, seq);

drop index if exists agent_conversation_turn_recent;

create index agent_conversation_turn_recent
    on agent_conversation_turn (agent_id, session_id, seq desc);
