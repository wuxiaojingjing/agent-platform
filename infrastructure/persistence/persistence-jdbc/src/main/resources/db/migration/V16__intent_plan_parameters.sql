alter table agent_intent_plan
    add column if not exists parameters jsonb not null default '{}'::jsonb;
