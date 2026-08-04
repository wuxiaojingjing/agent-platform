drop index if exists agent_task_active_per_session;
drop index if exists agent_task_active_per_agent_session;
create index agent_task_active_lookup on agent_task(agent_id, session_id, updated_at desc)
    where state in ('CREATED','CLARIFY_PENDING','REVIEW_PENDING','CONFIRM_PENDING','RUNNING');

drop index if exists agent_intent_plan_active_per_session;
drop index if exists agent_intent_plan_active_per_agent_session;
create index agent_intent_plan_active_lookup on agent_intent_plan(agent_id, session_id, updated_at desc)
    where state in ('IN_PROGRESS','WAITING_USER','WAITING_REVIEW','WAITING_CONFIRMATION');
