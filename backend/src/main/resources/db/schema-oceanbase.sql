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
  permissions_json longtext not null default '[]',
  created_at timestamp null,
  updated_at timestamp null
);

create table if not exists la_user_role (
  user_id varchar(64) not null,
  role_id varchar(64) not null,
  primary key (user_id, role_id)
);
