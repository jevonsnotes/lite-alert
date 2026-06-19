create table if not exists la_role (
  id varchar(64) primary key,
  name varchar(64) not null unique,
  description varchar(500) null,
  system_builtin boolean not null,
  permissions_json longtext null,
  created_at timestamp null,
  updated_at timestamp null
);

create table if not exists la_user_role (
  user_id varchar(64) not null,
  role_id varchar(64) not null,
  primary key(user_id, role_id)
);

insert ignore into la_role(id, name, description, system_builtin, permissions_json, created_at, updated_at)
values ('r_super_admin', 'SUPER_ADMIN', 'Built-in super administrator', true, '["DASHBOARD_VIEW","NAMESPACE_VIEW","NAMESPACE_CREATE","NAMESPACE_DISABLE","NAMESPACE_DELETE","TOPIC_VIEW","TOPIC_CREATE","TOPIC_UPDATE","TOPIC_PUBLISH","TOPIC_DISABLE","TOPIC_DELETE","APIKEY_VIEW","APIKEY_CREATE","APIKEY_UPDATE","APIKEY_ROTATE","APIKEY_DELETE","CONTACT_VIEW","CONTACT_CREATE","CONTACT_UPDATE","CONTACT_DELETE","AUDIT_VIEW","DELIVERY_VIEW","DELIVERY_PAYLOAD_READ","USER_VIEW","USER_CREATE","USER_UPDATE","USER_DELETE","ROLE_VIEW","ROLE_CREATE","ROLE_UPDATE","ROLE_DELETE","SYSTEM_SETTINGS_VIEW","SYSTEM_SETTINGS_UPDATE"]', current_timestamp, current_timestamp);

insert ignore into la_role(id, name, description, system_builtin, permissions_json, created_at, updated_at)
values ('r_normal_user', 'NORMAL_USER', 'Built-in normal user', true, '[]', current_timestamp, current_timestamp);

insert ignore into la_user_role(user_id, role_id)
select id, 'r_super_admin' from la_user where role = 'ADMIN';

insert ignore into la_user_role(user_id, role_id)
select id, 'r_normal_user' from la_user where role <> 'ADMIN';
