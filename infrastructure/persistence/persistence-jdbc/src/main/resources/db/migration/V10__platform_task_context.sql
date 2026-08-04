create table agent_platform_task (
    tenant_id varchar(128) not null,
    platform_task_id varchar(64) not null,
    agent_id varchar(128) not null,
    session_id varchar(128) not null,
    route_target_type varchar(32),
    route_target_id varchar(256),
    runtime_type varchar(32) not null,
    runtime_ref varchar(128),
    status varchar(16) not null,
    binding_state varchar(16) not null,
    close_reason varchar(64),
    route_decision_id varchar(128) not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (tenant_id, agent_id, platform_task_id),
    unique (tenant_id, agent_id, route_decision_id)
);
create unique index agent_platform_task_runtime_ref on agent_platform_task
    (tenant_id, agent_id, runtime_type, runtime_ref) where runtime_ref is not null;
create index agent_platform_task_session on agent_platform_task
    (tenant_id, agent_id, session_id, status);

create table agent_conversation_focus (
    tenant_id varchar(128) not null,
    frame_id varchar(64) not null,
    agent_id varchar(128) not null,
    session_id varchar(128) not null,
    subject_type varchar(32) not null,
    subject_ref varchar(128) not null,
    focus_state varchar(16) not null,
    version bigint not null default 0,
    last_focused_at timestamptz,
    suspended_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz not null default now(),
    primary key (tenant_id, agent_id, frame_id)
);
create unique index agent_focus_one_foreground on agent_conversation_focus
    (tenant_id, agent_id, session_id) where focus_state = 'FOREGROUND';
create unique index agent_focus_one_open_subject on agent_conversation_focus
    (tenant_id, agent_id, session_id, subject_type, subject_ref) where focus_state <> 'CLOSED';

create table agent_pending_switch (
    tenant_id varchar(128) not null,
    switch_id varchar(64) not null,
    agent_id varchar(128) not null,
    session_id varchar(128) not null,
    foreground_frame_id varchar(64) not null,
    foreground_frame_version bigint not null,
    source_turn_id varchar(128) not null,
    span_start int not null,
    span_end int not null,
    span_hash varchar(128) not null,
    state varchar(16) not null,
    resolved_turn_id varchar(128),
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    primary key (tenant_id, agent_id, switch_id)
);
create unique index agent_pending_switch_one on agent_pending_switch
    (tenant_id, agent_id, session_id) where state = 'PENDING';

create table agent_pending_goal (
    tenant_id varchar(128) not null,
    pending_goal_id varchar(64) not null,
    agent_id varchar(128) not null,
    session_id varchar(128) not null,
    switch_id varchar(64) not null,
    previous_frame_id varchar(64) not null,
    source_turn_id varchar(128) not null,
    span_start int not null,
    span_end int not null,
    span_hash varchar(128) not null,
    state varchar(32) not null,
    route_decision_id varchar(128),
    bound_platform_task_id varchar(64),
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (tenant_id, agent_id, pending_goal_id)
);
