create table if not exists la_role (
  id varchar(64) primary key,
  name varchar(64) not null unique,
  description varchar(500),
  system_builtin boolean not null,
  permissions_json clob,
  created_at timestamp,
  updated_at timestamp
);

create table if not exists la_user_role (
  user_id varchar(64) not null,
  role_id varchar(64) not null,
  primary key(user_id, role_id)
);

insert into la_role(id, name, description, system_builtin, permissions_json, created_at, updated_at)
select 'r_super_admin', 'SUPER_ADMIN', 'Built-in super administrator', true, '["DASHBOARD_VIEW","NAMESPACE_VIEW","NAMESPACE_CREATE","NAMESPACE_DISABLE","NAMESPACE_DELETE","TOPIC_VIEW","TOPIC_CREATE","TOPIC_UPDATE","TOPIC_PUBLISH","TOPIC_DISABLE","TOPIC_DELETE","APIKEY_VIEW","APIKEY_CREATE","APIKEY_UPDATE","APIKEY_ROTATE","APIKEY_DELETE","CONTACT_VIEW","CONTACT_CREATE","CONTACT_UPDATE","CONTACT_DELETE","AUDIT_VIEW","DELIVERY_VIEW","DELIVERY_PAYLOAD_READ","USER_VIEW","USER_CREATE","USER_UPDATE","USER_DELETE","ROLE_VIEW","ROLE_CREATE","ROLE_UPDATE","ROLE_DELETE","SYSTEM_SETTINGS_VIEW","SYSTEM_SETTINGS_UPDATE"]', current_timestamp, current_timestamp
where not exists (select 1 from la_role where id = 'r_super_admin');

insert into la_role(id, name, description, system_builtin, permissions_json, created_at, updated_at)
select 'r_normal_user', 'NORMAL_USER', 'Built-in normal user', true, '[]', current_timestamp, current_timestamp
where not exists (select 1 from la_role where id = 'r_normal_user');

insert into la_user_role(user_id, role_id)
select u.id, 'r_super_admin' from la_user u where u.role = 'ADMIN'
and not exists (select 1 from la_user_role ur where ur.user_id = u.id and ur.role_id = 'r_super_admin');

insert into la_user_role(user_id, role_id)
select u.id, 'r_normal_user' from la_user u where u.role <> 'ADMIN'
and not exists (select 1 from la_user_role ur where ur.user_id = u.id and ur.role_id = 'r_normal_user');
