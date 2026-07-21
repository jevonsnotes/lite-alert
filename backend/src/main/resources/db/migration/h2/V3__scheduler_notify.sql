-- Scheduler notify configs (outbound webhook definitions for scheduled-task notifications)

-- draft-track notify-config bindings on the task (published track lives in published_config_json.meta)
alter table la_scheduler_task add column if not exists notify_config_ids_json clob;

create table if not exists la_scheduler_notify_config (
  id varchar(64) primary key,
  owner_id varchar(64) not null,
  name varchar(128) not null,
  method varchar(16) not null,
  url varchar(1024) not null,
  headers_json clob,
  body_template clob,
  trigger_on varchar(16) not null default 'FAIL',
  enabled boolean not null default true,
  created_at timestamp not null,
  updated_at timestamp not null
);

create index if not exists idx_snc_owner on la_scheduler_notify_config(owner_id);

-- Grant notify permissions to built-in roles
insert into la_role_permission(role_id, permission)
select 'r_super_admin', p from (values
  ('SCHEDULER_NOTIFY_VIEW'),('SCHEDULER_NOTIFY_MANAGE')
) as v(p)
where not exists (select 1 from la_role_permission where role_id = 'r_super_admin' and permission = v.p);

insert into la_role_permission(role_id, permission)
select 'r_normal_user', p from (values
  ('SCHEDULER_NOTIFY_VIEW')
) as v(p)
where not exists (select 1 from la_role_permission where role_id = 'r_normal_user' and permission = v.p);
