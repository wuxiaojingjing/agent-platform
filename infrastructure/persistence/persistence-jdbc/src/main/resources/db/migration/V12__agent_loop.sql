create table agent_loop_run (
    tenant_id varchar(128) not null,
    loop_id varchar(64) not null,
    agent_id varchar(128) not null,
    session_id varchar(128) not null,
    root_task_id varchar(64),
    trace_id varchar(128),
    goal text not null,
    status varchar(32) not null,
    iteration int not null default 0,
    max_iterations int not null,
    candidate_ids jsonb not null default '[]',
    facts jsonb not null default '{}',
    pending_action jsonb,
    deadline timestamptz,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (tenant_id, agent_id, loop_id)
);
create index agent_loop_session on agent_loop_run(tenant_id,agent_id,session_id,updated_at desc);

create table agent_loop_step (
    tenant_id varchar(128) not null,
    agent_id varchar(128) not null,
    loop_id varchar(64) not null,
    step_index int not null,
    action jsonb not null,
    status varchar(32) not null,
    task_id varchar(64),
    delegation_id varchar(64),
    observation jsonb,
    reason_code varchar(128),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    primary key (tenant_id,agent_id,loop_id,step_index),
    foreign key (tenant_id,agent_id,loop_id) references agent_loop_run(tenant_id,agent_id,loop_id)
);
