create table if not exists agent_intent_plan_condition (
    plan_id              varchar(64) not null references agent_intent_plan(plan_id) on delete cascade,
    step_index           int not null,
    source_text          text not null,
    compiled_expression jsonb,
    outcome              varchar(24) not null,
    fact_digest          varchar(64) not null,
    model_version        varchar(128),
    prompt_version       varchar(128),
    created_at           timestamptz not null default now(),
    primary key (plan_id, step_index, fact_digest),
    constraint agent_intent_plan_condition_step_nonnegative check (step_index >= 0),
    constraint agent_intent_plan_condition_outcome check (
        outcome in ('RESOLVED', 'INVALID', 'UNRESOLVED')),
    constraint agent_intent_plan_condition_expression check (
        (outcome = 'RESOLVED' and compiled_expression is not null)
        or (outcome <> 'RESOLVED' and compiled_expression is null))
);
