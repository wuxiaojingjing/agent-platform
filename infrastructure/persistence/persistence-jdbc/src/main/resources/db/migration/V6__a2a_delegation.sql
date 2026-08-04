-- A2A 委托台账（架构草案 v0.2 §6.2）。
--
-- 为什么 delegation_id 的唯一性写在数据库而不只写在服务层：
-- 服务层判重会被绕过——绕过服务层直改库这条路径，FP-25 是靠数据库 CHECK 兜住的，
-- 这里照同一个思路。不定这条的后果很具体：上游重投一次委托，下游走两遍完整流程、
-- 发两把各自合法的本地幂等键，两笔转账都「合规」，两侧日志都正常。

create table if not exists agent_delegation (
    delegation_id    varchar(64) primary key,
    tenant_id        varchar(64),
    source_agent_id  varchar(64)  not null,
    target_agent_id  varchar(64)  not null,
    root_task_id     varchar(64),
    parent_task_id   varchar(64),
    source_task_id   varchar(64),
    trace_id         varchar(64)  not null,
    mode             varchar(8)   not null,
    capability_id    varchar(128),
    goal             text,
    delegation_path  jsonb        not null default '[]',
    depth            int          not null default 0,
    deadline         timestamptz,

    -- 首次结果。二次到达同一 delegation_id 时原样返回这里的内容，
    -- 不重新建档、不重跑护栏——包括首次结果是 PARTIAL 的情况（§6.2 第 3 条）
    outcome          varchar(24),
    facts            jsonb        not null default '{}',
    missing_slots    jsonb        not null default '[]',
    reason_code      varchar(64),
    settled_at       timestamptz,

    created_at       timestamptz  not null default now(),

    -- 终态必须带结局：settled 了却没有 outcome，二次到达就会读到一份空回执，
    -- 而空回执在上游看来和「还没办完」无法区分
    constraint agent_delegation_settled_has_outcome
        check (settled_at is null or outcome is not null),

    -- 深度上限是 3（§6.3）。写进 CHECK 而不只在网关里判，理由同上：
    -- 直改库塞一条深度 9 的委托，网关的判定就绕过去了
    constraint agent_delegation_depth_bounded
        check (depth >= 0 and depth <= 8)
);

create index if not exists agent_delegation_by_root
    on agent_delegation (root_task_id);

create index if not exists agent_delegation_by_trace
    on agent_delegation (trace_id);
