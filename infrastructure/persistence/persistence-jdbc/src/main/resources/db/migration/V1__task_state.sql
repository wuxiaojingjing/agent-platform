-- 中控任务态。领域 Agent 不持有任务真值，这三张表是唯一事实来源（实施架构 §8）。

create table agent_task (
    task_id          varchar(64) primary key,
    trace_id         varchar(64)  not null,
    session_id       varchar(64)  not null,
    user_id          varchar(64),
    capability_id    varchar(128) not null,
    domain           varchar(64),
    goal             text,
    state            varchar(32)  not null,
    risk_level       varchar(8)   not null,
    source           varchar(16)  not null,
    parameters       jsonb        not null default '{}',
    pending_slot     varchar(64),
    expected_answers jsonb        not null default '[]',
    clarify_rounds   int          not null default 0,
    guardrail_status varchar(16)  not null default 'PENDING',
    guardrail_codes  jsonb        not null default '[]',
    idempotency_key  varchar(128),
    result_payload   jsonb,
    failure_class    varchar(16),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),

    -- 实施架构 §8.4：幂等键即可执行凭据，护栏未过不得存在。
    -- 这条约束写在数据库而不只写在代码里，是因为绕过服务层直接改库的路径同样必须被堵死
    constraint agent_task_idem_requires_guardrail
        check (idempotency_key is null or guardrail_status = 'PASSED')
);

-- 一个会话同时只应有一个活跃任务。并行任务是后续切片的能力，
-- 现在放开会让「续轮补充的是哪个任务」变成歧义
create unique index agent_task_active_per_session
    on agent_task (session_id)
    where state in ('CREATED', 'CLARIFY_PENDING', 'CONFIRM_PENDING', 'RUNNING');

create table agent_idempotency (
    idempotency_key varchar(128) primary key,
    task_id         varchar(64)  not null references agent_task (task_id),
    capability_id   varchar(128) not null,
    created_at      timestamptz  not null default now()
);

-- 状态迁移全量留痕。任务最终状态回答不了「它是怎么走到这一步的」，
-- 而事故复盘要的恰恰是路径
create table agent_task_transition (
    id         bigserial primary key,
    task_id    varchar(64) not null,
    from_state varchar(32),
    to_state   varchar(32) not null,
    reason     varchar(128),
    trace_id   varchar(64),
    created_at timestamptz not null default now()
);

create index agent_task_transition_task on agent_task_transition (task_id, id);
