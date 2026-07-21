-- Scheduled tasks (API task runner) + call records

create table if not exists la_scheduler_task (
  id varchar(64) primary key,
  owner_id varchar(64) not null,
  name varchar(128) not null,
  description varchar(500),
  task_type varchar(16) not null,
  cron varchar(128) not null,
  enabled boolean not null default true,
  status varchar(16) not null default 'DRAFT',
  draft_config_json clob,
  published_config_json clob,
  published_at timestamp,
  created_at timestamp not null,
  updated_at timestamp not null
);

create index if not exists idx_sct_status on la_scheduler_task(status);
create index if not exists idx_sct_owner on la_scheduler_task(owner_id);

create table if not exists la_scheduler_task_call (
  id varchar(64) primary key,
  task_id varchar(64) not null,
  triggered_at timestamp not null,
  method varchar(16),
  url varchar(1024),
  http_status int,
  duration_ms bigint,
  success boolean not null,
  assertion_passed boolean,
  error_message clob,
  response_excerpt clob,
  created_at timestamp not null
);

create index if not exists idx_sctc_task_id on la_scheduler_task_call(task_id);
create index if not exists idx_sctc_triggered_at on la_scheduler_task_call(triggered_at);
create index if not exists idx_sctc_success on la_scheduler_task_call(success);

-- Grant scheduler permissions to built-in roles
insert into la_role_permission(role_id, permission)
select 'r_super_admin', p from (values
  ('SCHEDULER_TASK_VIEW'),('SCHEDULER_TASK_VIEW_ALL'),('SCHEDULER_TASK_MANAGE'),('SCHEDULER_TASK_PUBLISH'),
  ('SCHEDULER_CALL_VIEW'),('SCHEDULER_CALL_VIEW_ALL')
) as v(p)
where not exists (select 1 from la_role_permission where role_id = 'r_super_admin' and permission = v.p);

insert into la_role_permission(role_id, permission)
select 'r_normal_user', p from (values
  ('SCHEDULER_TASK_VIEW'),('SCHEDULER_CALL_VIEW')
) as v(p)
where not exists (select 1 from la_role_permission where role_id = 'r_normal_user' and permission = v.p);
