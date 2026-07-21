-- Scheduled tasks (API task runner) + call records

create table if not exists la_scheduler_task (
  id varchar(64) not null primary key,
  owner_id varchar(64) not null,
  name varchar(128) not null,
  description varchar(500) null,
  task_type varchar(16) not null,
  cron varchar(128) not null,
  enabled int not null default 1,
  status varchar(16) not null default 'DRAFT',
  draft_config_json longtext null,
  published_config_json longtext null,
  published_at timestamp null,
  created_at timestamp not null,
  updated_at timestamp not null
);

create index idx_sct_status on la_scheduler_task(status);
create index idx_sct_owner on la_scheduler_task(owner_id);

create table if not exists la_scheduler_task_call (
  id varchar(64) not null primary key,
  task_id varchar(64) not null,
  triggered_at timestamp not null,
  method varchar(16) null,
  url varchar(1024) null,
  http_status int null,
  duration_ms bigint null,
  success int not null,
  assertion_passed int null,
  error_message longtext null,
  response_excerpt longtext null,
  created_at timestamp not null
);

create index idx_sctc_task_id on la_scheduler_task_call(task_id);
create index idx_sctc_triggered_at on la_scheduler_task_call(triggered_at);
create index idx_sctc_success on la_scheduler_task_call(success);

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
