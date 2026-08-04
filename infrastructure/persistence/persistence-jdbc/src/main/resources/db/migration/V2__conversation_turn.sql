-- 对话轮次（FP-28）。
--
-- 版本号与 agent-platform-orchestrator 的迁移共用同一条时间线：Flyway 把 classpath 上所有
-- db/migration 合并成一个版本序列，两个模块各自从 V1 开始编号会直接撞车。
-- ModuleDependencyTest 里有一条守卫盯着版本唯一性。

create table agent_conversation_turn (
    session_id      varchar(64)  not null,
    seq             bigint       not null,
    trace_id        varchar(64),
    task_id         varchar(64),
    user_text       text,
    decision        varchar(32),
    reason_code     varchar(64),
    capability_id   varchar(128),
    -- 受控枚举，不存自然语言描述（FP-28）。列宽刻意给得紧，
    -- 宽到能塞下一句话的列，早晚会被塞进一句话。
    outcome         varchar(24),
    pending         varchar(16)  not null default 'NONE',
    pending_options jsonb        not null default '[]',
    facts           jsonb        not null default '{}',
    at              timestamptz  not null default now(),
    primary key (session_id, seq)
);

-- 取最近 N 轮是唯一的读法，索引按它建。
create index agent_conversation_turn_recent
    on agent_conversation_turn (session_id, seq desc);
