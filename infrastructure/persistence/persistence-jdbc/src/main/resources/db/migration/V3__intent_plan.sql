-- 多意图计划（FP-18 / S4 场景 3）。
--
-- 与 agent_task 分开存，不是为了整齐，是因为 agent_task 上有
-- agent_task_active_per_session：一个会话同时只允许一条活跃任务。多意图天然是一个会话里
-- 好几件事，塞进 agent_task 只有两条路——放开那条唯一索引（于是「续轮补充的是哪个任务」
-- 重新变成歧义），或者一次只建一条而把「还剩哪几件」丢掉。计划另存是第三条路：
-- 计划回答「用户说了哪几件事、办到第几件」，任务仍然一次只有一个。
--
-- 版本号与 agent-platform-context 的 V2 共用同一条时间线：Flyway 把 classpath 上所有 db/migration
-- 合并成一个版本序列，各模块从 V1 起各编各的会直接撞车。

create table agent_intent_plan (
    plan_id     varchar(64)  primary key,
    session_id  varchar(64)  not null,
    trace_id    varchar(64),
    original    text         not null,
    -- 子意图数组。整体存 jsonb 而不是拆成子表：计划是一次性写入、整体读出的，
    -- 拆表换来的是每次多一次 join，换不到任何可查询性——没有人会按子意图检索
    items       jsonb        not null,
    -- 办到第几件。指向 items 的下标，等于长度表示全办完
    cursor      int          not null default 0,
    source      varchar(16)  not null,
    state       varchar(24)  not null,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),

    -- 游标越界意味着「办到第几件」这个答案本身是错的，下游会按不存在的下标取事
    constraint agent_intent_plan_cursor_in_range
        check (cursor >= 0 and cursor <= jsonb_array_length(items)),
    -- 计划至少两件事。长度为 1 的计划会让下游以为这是多意图，
    -- 与 IntentPlan 构造期的那条校验是同一条规则的两处落点
    constraint agent_intent_plan_at_least_two
        check (jsonb_array_length(items) >= 2)
);

-- 一个会话同时只应有一份在办的计划。与 agent_task 的活跃唯一索引同一动机：
-- 两份在办的计划会让「用户刚才选的是哪一件」失去唯一解
create unique index agent_intent_plan_active_per_session
    on agent_intent_plan (session_id)
    where state = 'IN_PROGRESS';
