create table if not exists la_schema_version (
  version integer not null,
  initialized_at timestamp not null
);

create table if not exists la_user (
  id varchar(64) primary key,
  username varchar(64) not null unique,
  password_hash varchar(255) not null,
  enabled boolean not null,
  created_at timestamp null,
  created_by varchar(64) null,
  updated_at timestamp null,
  last_login_at timestamp null
);

create table if not exists la_namespace (
  id varchar(64) primary key,
  name varchar(64) not null unique,
  owner_id varchar(64) not null,
  description varchar(500) null,
  status varchar(16) not null default 'ACTIVE',
  disabled_at timestamp null,
  disabled_by varchar(64) null,
  created_at timestamp null,
  updated_at timestamp null
);

create table if not exists la_topic (
  id varchar(64) primary key,
  namespace_id varchar(64) not null,
  namespace_name varchar(64) null,
  name varchar(64) not null,
  description varchar(500) null,
  owner_id varchar(64) not null,
  status varchar(16) not null,
  sync boolean not null default false,
  sync_timeout int null,
  auth_json text null,
  inbound_format_json text null,
  created_at timestamp null,
  updated_at timestamp null,
  published_at timestamp null,
  constraint uk_topic_namespace_name unique(namespace_id, name)
);

create table if not exists la_topic_channel_template (
  id bigserial primary key,
  topic_id varchar(64) not null,
  channel_type varchar(16) not null,
  subject varchar(500) null,
  body text null,
  output_format varchar(16) null,
  output_template text null,
  output_xml_template text null,
  transform_json text null,
  response_check_json text null,
  created_at timestamp null,
  updated_at timestamp null,
  constraint uk_tct_topic_channel unique(topic_id, channel_type)
);

create table if not exists la_api_key (
  id varchar(64) primary key,
  owner_id varchar(64) not null,
  name varchar(128) null,
  prefix varchar(32) null,
  key_hash varchar(128) not null,
  valid_from timestamp null,
  valid_until timestamp null,
  status varchar(16) not null,
  created_at timestamp null,
  last_used_at timestamp null,
  usage_count bigint not null default 0,
  rotate_count bigint not null default 0,
  rate_limit_per_minute int null
);

create table if not exists la_api_key_scope (
  id bigserial primary key,
  api_key_id varchar(64) not null,
  scope_type varchar(16) not null,
  scope_id varchar(64) not null,
  created_at timestamp null,
  constraint uk_aks_key_type_id unique(api_key_id, scope_type, scope_id)
);

create table if not exists la_notify_target (
  id varchar(64) primary key,
  user_id varchar(64) not null,
  label varchar(128) null,
  type varchar(32) not null,
  endpoint text not null,
  secret text null,
  enabled boolean not null,
  created_at timestamp null
);

create table if not exists la_topic_contact (
  id bigserial primary key,
  topic_id varchar(64) not null,
  contact_id varchar(64) not null,
  created_at timestamp null,
  constraint uk_tc_topic_contact unique(topic_id, contact_id)
);

create table if not exists la_system_settings (
  id varchar(64) primary key,
  settings_json text not null,
  updated_at timestamp null
);

create table if not exists la_audit_log (
  id bigserial primary key,
  ts timestamp not null,
  type varchar(128) not null,
  actor varchar(64) null,
  trace_id varchar(128) null,
  topic_id varchar(64) null,
  api_key_id varchar(64) null,
  attrs_json text null
);

create index if not exists idx_al_topic_id on la_audit_log(topic_id);
create index if not exists idx_al_api_key_id on la_audit_log(api_key_id);

create table if not exists la_notify_delivery (
  id varchar(64) primary key,
  trace_id varchar(128) null,
  topic_id varchar(64) not null,
  target_id varchar(64) not null,
  channel varchar(32) not null,
  payload_json text not null,
  status varchar(32) not null,
  attempt int not null default 0,
  max_attempts int not null default 5,
  next_retry_at timestamp not null,
  locked_by varchar(128) null,
  locked_at timestamp null,
  last_error text null,
  created_at timestamp not null,
  updated_at timestamp not null,
  finished_at timestamp null
);

create index if not exists idx_nd_topic_id on la_notify_delivery(topic_id);
create index if not exists idx_nd_status on la_notify_delivery(status);
create index if not exists idx_nd_next_retry on la_notify_delivery(next_retry_at);
create index if not exists idx_nd_channel on la_notify_delivery(channel);
create index if not exists idx_nd_locked_by on la_notify_delivery(locked_by);
create index if not exists idx_nd_trace_id on la_notify_delivery(trace_id);

create table if not exists la_role (
  id varchar(64) primary key,
  name varchar(64) not null unique,
  description varchar(500) null,
  system_builtin boolean not null default false,
  created_at timestamp null,
  updated_at timestamp null
);

create table if not exists la_role_permission (
  role_id varchar(64) not null,
  permission varchar(64) not null,
  primary key (role_id, permission)
);

create table if not exists la_user_role (
  user_id varchar(64) not null,
  role_id varchar(64) not null,
  primary key (user_id, role_id)
);

-- Seed admin user (password: admin123, stored as bcrypt(md5("admin123")))
insert into la_user(id, username, password_hash, enabled, created_at)
select 'u_admin', 'admin', '$2a$10$npZpvBMX1imudiwgxcK69epwpT8yhikyVIX4Y/unzzjztCQeZDfWe', true, now()
where not exists (select 1 from la_user where id = 'u_admin');

-- Seed built-in roles
insert into la_role(id, name, description, system_builtin, created_at)
select 'r_super_admin', '超级管理员', 'All permissions', true, now()
where not exists (select 1 from la_role where id = 'r_super_admin');

insert into la_role_permission(role_id, permission)
select 'r_super_admin', p from unnest(array[
  'DASHBOARD_VIEW','STATS_VIEW','STATS_VIEW_ALL','NAMESPACE_VIEW','NAMESPACE_VIEW_ALL','NAMESPACE_CREATE','NAMESPACE_UPDATE','NAMESPACE_DISABLE','NAMESPACE_DELETE',
  'TOPIC_VIEW','TOPIC_VIEW_ALL','TOPIC_CREATE','TOPIC_UPDATE','TOPIC_PUBLISH','TOPIC_DISABLE','TOPIC_DELETE',
  'APIKEY_VIEW','APIKEY_VIEW_ALL','APIKEY_CREATE','APIKEY_UPDATE','APIKEY_ROTATE','APIKEY_DELETE',
  'CONTACT_VIEW','CONTACT_VIEW_ALL','CONTACT_CREATE','CONTACT_UPDATE','CONTACT_DELETE',
  'AUDIT_VIEW','AUDIT_VIEW_ALL','DELIVERY_VIEW','DELIVERY_PAYLOAD_READ',
  'USER_VIEW','USER_CREATE','USER_UPDATE','USER_DELETE',
  'ROLE_VIEW','ROLE_CREATE','ROLE_UPDATE','ROLE_DELETE',
  'SYSTEM_HEALTH_VIEW','SYSTEM_SETTINGS_VIEW','SYSTEM_SETTINGS_UPDATE',
  'MAIL_CONFIG_VIEW','MAIL_CONFIG_UPDATE','SMTP_TEST']) p
where not exists (select 1 from la_role_permission where role_id = 'r_super_admin');

insert into la_role(id, name, description, system_builtin, created_at)
select 'r_normal_user', '普通用户', 'Basic user permissions', true, now()
where not exists (select 1 from la_role where id = 'r_normal_user');

insert into la_role_permission(role_id, permission)
select 'r_normal_user', p from unnest(array[
  'DASHBOARD_VIEW','STATS_VIEW','NAMESPACE_VIEW','NAMESPACE_CREATE','NAMESPACE_UPDATE','NAMESPACE_DISABLE','NAMESPACE_DELETE',
  'TOPIC_VIEW','TOPIC_CREATE','TOPIC_UPDATE','TOPIC_PUBLISH','TOPIC_DISABLE','TOPIC_DELETE',
  'APIKEY_VIEW','APIKEY_CREATE','APIKEY_UPDATE','APIKEY_ROTATE','APIKEY_DELETE',
  'CONTACT_VIEW','CONTACT_CREATE','CONTACT_UPDATE','CONTACT_DELETE',
  'AUDIT_VIEW','DELIVERY_VIEW','DELIVERY_PAYLOAD_READ']) p
where not exists (select 1 from la_role_permission where role_id = 'r_normal_user');

-- Seed admin role assignment
insert into la_user_role(user_id, role_id)
select 'u_admin', 'r_super_admin'
where not exists (select 1 from la_user_role where user_id = 'u_admin' and role_id = 'r_super_admin');
