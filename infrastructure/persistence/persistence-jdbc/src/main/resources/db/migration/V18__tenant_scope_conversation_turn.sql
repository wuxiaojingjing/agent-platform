-- Conversation memory must use the same tenant + agent + session scope as focus and Runtime state.
-- Existing rows predate tenant ownership and remain in the explicit unscoped partition.

alter table agent_conversation_turn
    add column if not exists tenant_id varchar(128) not null default '-';

alter table agent_conversation_turn
    drop constraint if exists agent_conversation_turn_pkey;

alter table agent_conversation_turn
    add primary key (tenant_id, agent_id, session_id, seq);

drop index if exists agent_conversation_turn_recent;

create index agent_conversation_turn_recent
    on agent_conversation_turn (tenant_id, agent_id, session_id, seq desc);
