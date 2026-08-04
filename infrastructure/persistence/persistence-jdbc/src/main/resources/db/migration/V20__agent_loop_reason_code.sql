alter table agent_loop_run
    add column if not exists reason_code varchar(128);
