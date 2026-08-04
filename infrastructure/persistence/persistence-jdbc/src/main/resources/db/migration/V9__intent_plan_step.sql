create table if not exists agent_intent_plan_step (
    plan_id        varchar(64)  not null references agent_intent_plan(plan_id) on delete cascade,
    step_index     int          not null,
    capability_id varchar(128) not null,
    task_id        varchar(64),
    status         varchar(16)  not null,
    failure_class  varchar(16)  not null,
    result_facts   jsonb        not null default '{}',
    reason_code    varchar(64),
    completed_at   timestamptz  not null default now(),
    primary key (plan_id, step_index),
    constraint agent_intent_plan_step_index_nonnegative check (step_index >= 0)
);

create index if not exists agent_intent_plan_step_task
    on agent_intent_plan_step (task_id) where task_id is not null;
