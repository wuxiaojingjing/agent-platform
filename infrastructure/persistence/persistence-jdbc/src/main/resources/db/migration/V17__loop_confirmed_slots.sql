alter table agent_loop_run
    add column confirmed_slots jsonb not null default '{}',
    add column pending_slots jsonb not null default '[]';
