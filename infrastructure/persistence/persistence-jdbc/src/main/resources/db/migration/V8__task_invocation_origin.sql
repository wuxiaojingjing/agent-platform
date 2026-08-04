alter table agent_task
    add column if not exists invocation_origin varchar(16) not null default 'LOCAL';

drop index if exists agent_task_source_invocation_unique;

create unique index if not exists agent_task_invocation_origin_unique
    on agent_task (agent_id, invocation_origin, source_invocation_id)
    where source_invocation_id is not null;
