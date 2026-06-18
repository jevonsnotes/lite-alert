create table if not exists la_schema_version (
  version integer not null,
  initialized_at timestamp not null
);

create table if not exists la_user (
  id varchar(64) primary key,
  username varchar(64) not null unique,
  password_hash varchar(255) not null,
  role varchar(16) not null,
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
  auth_json text null,
  inbound_format_json text null,
  templates_json text null,
  transform_json text null,
  notify_template_json text null,
  created_at timestamp null,
  updated_at timestamp null,
  published_at timestamp null,
  constraint uk_topic_namespace_name unique(namespace_id, name)
);

create table if not exists la_api_key (
  id varchar(64) primary key,
  owner_id varchar(64) not null,
  name varchar(128) null,
  prefix varchar(32) null,
  key_hash varchar(128) not null,
  valid_from timestamp null,
  valid_until timestamp null,
  scopes_json text null,
  status varchar(16) not null,
  created_at timestamp null,
  last_used_at timestamp null,
  usage_count bigint not null default 0,
  rotate_count bigint not null default 0
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

create table if not exists la_subscription (
  topic_id varchar(64) primary key,
  contact_ids_json text null,
  updated_at timestamp null
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
  attrs_json text null
);
