alter table agent_intent_plan
    add column if not exists state_version bigint not null default 0;

alter table agent_intent_plan
    add column if not exists pending_task_id varchar(64),
    add column if not exists pending_slot varchar(64),
    add column if not exists expected_answers jsonb not null default '[]'::jsonb;

update agent_intent_plan
set state_version = cursor
where state_version = 0 and cursor > 0;
