-- Full ordered message items for model/user transcript consistency.
-- Facts remain in the dedicated facts column and retain execution authority.

alter table agent_conversation_turn
    add column if not exists messages jsonb not null default '[]'::jsonb;
