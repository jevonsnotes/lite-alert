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
  attrs_json longtext null
);

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
  permissions_json longtext not null default '[]',
  created_at timestamp null,
  updated_at timestamp null
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
insert ignore into la_role(id, name, description, system_builtin, permissions_json, created_at)
values ('r_super_admin', '超级管理员', 'All permissions', true,
  '["DASHBOARD_VIEW","STATS_VIEW","STATS_VIEW_ALL","NAMESPACE_VIEW","NAMESPACE_VIEW_ALL","NAMESPACE_CREATE","NAMESPACE_UPDATE","NAMESPACE_DISABLE","NAMESPACE_DELETE","TOPIC_VIEW","TOPIC_VIEW_ALL","TOPIC_CREATE","TOPIC_UPDATE","TOPIC_PUBLISH","TOPIC_DISABLE","TOPIC_DELETE","APIKEY_VIEW","APIKEY_VIEW_ALL","APIKEY_CREATE","APIKEY_UPDATE","APIKEY_ROTATE","APIKEY_DELETE","CONTACT_VIEW","CONTACT_VIEW_ALL","CONTACT_CREATE","CONTACT_UPDATE","CONTACT_DELETE","AUDIT_VIEW","AUDIT_VIEW_ALL","DELIVERY_VIEW","DELIVERY_PAYLOAD_READ","USER_VIEW","USER_CREATE","USER_UPDATE","USER_DELETE","ROLE_VIEW","ROLE_CREATE","ROLE_UPDATE","ROLE_DELETE","SYSTEM_HEALTH_VIEW","SYSTEM_SETTINGS_VIEW","SYSTEM_SETTINGS_UPDATE","MAIL_CONFIG_VIEW","MAIL_CONFIG_UPDATE","SMTP_TEST"]',
  current_timestamp);

insert ignore into la_role(id, name, description, system_builtin, permissions_json, created_at)
values ('r_normal_user', '普通用户', 'Basic user permissions', true,
  '["DASHBOARD_VIEW","STATS_VIEW","NAMESPACE_VIEW","NAMESPACE_CREATE","NAMESPACE_UPDATE","NAMESPACE_DISABLE","NAMESPACE_DELETE","TOPIC_VIEW","TOPIC_CREATE","TOPIC_UPDATE","TOPIC_PUBLISH","TOPIC_DISABLE","TOPIC_DELETE","APIKEY_VIEW","APIKEY_CREATE","APIKEY_UPDATE","APIKEY_ROTATE","APIKEY_DELETE","CONTACT_VIEW","CONTACT_CREATE","CONTACT_UPDATE","CONTACT_DELETE","AUDIT_VIEW","DELIVERY_VIEW","DELIVERY_PAYLOAD_READ"]',
  current_timestamp);
