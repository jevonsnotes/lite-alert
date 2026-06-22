create table if not exists la_schema_version (
  version int not null,
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
  sync tinyint(1) not null default 0,
  sync_timeout int null,
  auth_json longtext null,
  inbound_format_json longtext null,
  created_at timestamp null,
  updated_at timestamp null,
  published_at timestamp null,
  unique key uk_topic_namespace_name(namespace_id, name)
);

create table if not exists la_topic_channel_template (
  id bigint auto_increment primary key,
  topic_id varchar(64) not null,
  channel_type varchar(16) not null,
  subject varchar(500) null,
  body longtext null,
  output_format varchar(16) null,
  output_template longtext null,
  output_xml_template varchar(5000) null,
  transform_json longtext null,
  response_check_json longtext null,
  created_at timestamp null,
  updated_at timestamp null,
  unique key uk_tct_topic_channel(topic_id, channel_type)
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
  id bigint auto_increment primary key,
  api_key_id varchar(64) not null,
  scope_type varchar(16) not null,
  scope_id varchar(64) not null,
  created_at timestamp null,
  unique key uk_aks_key_type_id(api_key_id, scope_type, scope_id)
);

create table if not exists la_notify_target (
  id varchar(64) primary key,
  user_id varchar(64) not null,
  label varchar(128) null,
  type varchar(32) not null,
  endpoint longtext not null,
  secret longtext null,
  enabled boolean not null,
  created_at timestamp null
);

create table if not exists la_topic_contact (
  id bigint auto_increment primary key,
  topic_id varchar(64) not null,
  contact_id varchar(64) not null,
  created_at timestamp null,
  unique key uk_tc_topic_contact(topic_id, contact_id)
);

create table if not exists la_system_settings (
  id varchar(64) primary key,
  settings_json longtext not null,
  updated_at timestamp null
);

create table if not exists la_audit_log (
  id bigint primary key auto_increment,
  ts timestamp not null,
  type varchar(128) not null,
  actor varchar(64) null,
  trace_id varchar(128) null,
  topic_id varchar(64) null,
  api_key_id varchar(64) null,
  attrs_json longtext null
);

create index if not exists idx_al_topic_id on la_audit_log(topic_id);
create index if not exists idx_al_api_key_id on la_audit_log(api_key_id);

create table if not exists la_notify_delivery (
  id varchar(64) primary key,
  trace_id varchar(128) null,
  topic_id varchar(64) not null,
  target_id varchar(64) not null,
  channel varchar(32) not null,
  payload_json longtext not null,
  status varchar(32) not null,
  attempt int not null default 0,
  max_attempts int not null default 5,
  next_retry_at timestamp not null,
  locked_by varchar(128) null,
  locked_at timestamp null,
  last_error longtext null,
  created_at timestamp not null,
  updated_at timestamp not null,
  finished_at timestamp null
);

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

-- Seed admin user
insert ignore into la_user(id, username, password_hash, enabled, created_at)
values ('u_admin', 'admin', '$2a$10$npZpvBMX1imudiwgxcK69epwpT8yhikyVIX4Y/unzzjztCQeZDfWe', true, current_timestamp);

-- Seed built-in roles
insert ignore into la_role(id, name, description, system_builtin, created_at)
values ('r_super_admin', '超级管理员', 'All permissions', true, current_timestamp);

insert ignore into la_role_permission(role_id, permission) values
('r_super_admin', 'DASHBOARD_VIEW'), ('r_super_admin', 'STATS_VIEW'), ('r_super_admin', 'STATS_VIEW_ALL'),
('r_super_admin', 'NAMESPACE_VIEW'), ('r_super_admin', 'NAMESPACE_VIEW_ALL'), ('r_super_admin', 'NAMESPACE_CREATE'),
('r_super_admin', 'NAMESPACE_UPDATE'), ('r_super_admin', 'NAMESPACE_DISABLE'), ('r_super_admin', 'NAMESPACE_DELETE'),
('r_super_admin', 'TOPIC_VIEW'), ('r_super_admin', 'TOPIC_VIEW_ALL'), ('r_super_admin', 'TOPIC_CREATE'),
('r_super_admin', 'TOPIC_UPDATE'), ('r_super_admin', 'TOPIC_PUBLISH'), ('r_super_admin', 'TOPIC_DISABLE'),
('r_super_admin', 'TOPIC_DELETE'), ('r_super_admin', 'APIKEY_VIEW'), ('r_super_admin', 'APIKEY_VIEW_ALL'),
('r_super_admin', 'APIKEY_CREATE'), ('r_super_admin', 'APIKEY_UPDATE'), ('r_super_admin', 'APIKEY_ROTATE'),
('r_super_admin', 'APIKEY_DELETE'), ('r_super_admin', 'CONTACT_VIEW'), ('r_super_admin', 'CONTACT_VIEW_ALL'),
('r_super_admin', 'CONTACT_CREATE'), ('r_super_admin', 'CONTACT_UPDATE'), ('r_super_admin', 'CONTACT_DELETE'),
('r_super_admin', 'AUDIT_VIEW'), ('r_super_admin', 'AUDIT_VIEW_ALL'), ('r_super_admin', 'DELIVERY_VIEW'),
('r_super_admin', 'DELIVERY_PAYLOAD_READ'), ('r_super_admin', 'USER_VIEW'), ('r_super_admin', 'USER_CREATE'),
('r_super_admin', 'USER_UPDATE'), ('r_super_admin', 'USER_DELETE'), ('r_super_admin', 'ROLE_VIEW'),
('r_super_admin', 'ROLE_CREATE'), ('r_super_admin', 'ROLE_UPDATE'), ('r_super_admin', 'ROLE_DELETE'),
('r_super_admin', 'SYSTEM_HEALTH_VIEW'), ('r_super_admin', 'SYSTEM_SETTINGS_VIEW'), ('r_super_admin', 'SYSTEM_SETTINGS_UPDATE'),
('r_super_admin', 'MAIL_CONFIG_VIEW'), ('r_super_admin', 'MAIL_CONFIG_UPDATE'), ('r_super_admin', 'SMTP_TEST');

insert ignore into la_role(id, name, description, system_builtin, created_at)
values ('r_normal_user', '普通用户', 'Basic user permissions', true, current_timestamp);

insert ignore into la_role_permission(role_id, permission) values
('r_normal_user', 'DASHBOARD_VIEW'), ('r_normal_user', 'STATS_VIEW'),
('r_normal_user', 'NAMESPACE_VIEW'), ('r_normal_user', 'NAMESPACE_CREATE'), ('r_normal_user', 'NAMESPACE_UPDATE'),
('r_normal_user', 'NAMESPACE_DISABLE'), ('r_normal_user', 'NAMESPACE_DELETE'),
('r_normal_user', 'TOPIC_VIEW'), ('r_normal_user', 'TOPIC_CREATE'), ('r_normal_user', 'TOPIC_UPDATE'),
('r_normal_user', 'TOPIC_PUBLISH'), ('r_normal_user', 'TOPIC_DISABLE'), ('r_normal_user', 'TOPIC_DELETE'),
('r_normal_user', 'APIKEY_VIEW'), ('r_normal_user', 'APIKEY_CREATE'), ('r_normal_user', 'APIKEY_UPDATE'),
('r_normal_user', 'APIKEY_ROTATE'), ('r_normal_user', 'APIKEY_DELETE'), ('r_normal_user', 'CONTACT_VIEW'),
('r_normal_user', 'CONTACT_CREATE'), ('r_normal_user', 'CONTACT_UPDATE'), ('r_normal_user', 'CONTACT_DELETE'),
('r_normal_user', 'AUDIT_VIEW'), ('r_normal_user', 'DELIVERY_VIEW'), ('r_normal_user', 'DELIVERY_PAYLOAD_READ');
