alter table agent_loop_step
    add column if not exists claimed_at timestamptz;

create index if not exists agent_loop_claim_recovery
    on agent_loop_step(tenant_id, agent_id, loop_id, status, claimed_at)
    where status = 'CLAIMED';
