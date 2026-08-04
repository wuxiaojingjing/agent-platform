alter table agent_task
    add column if not exists source_invocation_id varchar(128),
    add column if not exists result_status varchar(16);

create unique index if not exists agent_task_source_invocation_unique
    on agent_task (agent_id, source, source_invocation_id)
    where source_invocation_id is not null;
