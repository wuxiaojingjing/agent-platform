-- 架构草案阶段 1：任务与多意图计划按 agent_id 分片。
--
-- 活跃唯一索引从「每会话一条」改为「每 Agent 每会话一条」——两个 Agent 共享基础设施时，
-- 不得因 sessionId 碰巧相同而互相占住活跃位。

alter table agent_task
    add column if not exists agent_id varchar(64) not null default 'agent.entry';

drop index if exists agent_task_active_per_session;

create unique index agent_task_active_per_agent_session
    on agent_task (agent_id, session_id)
    where state in ('CREATED', 'CLARIFY_PENDING', 'CONFIRM_PENDING', 'RUNNING');

alter table agent_intent_plan
    add column if not exists agent_id varchar(64) not null default 'agent.entry';

drop index if exists agent_intent_plan_active_per_session;

create unique index agent_intent_plan_active_per_agent_session
    on agent_intent_plan (agent_id, session_id)
    where state = 'IN_PROGRESS';
