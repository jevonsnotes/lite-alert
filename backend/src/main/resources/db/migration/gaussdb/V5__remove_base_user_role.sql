insert into la_user_role(user_id, role_id)
select u.id, 'r_super_admin' from la_user u where u.role = 'ADMIN'
and not exists (select 1 from la_user_role ur where ur.user_id = u.id and ur.role_id = 'r_super_admin');

insert into la_user_role(user_id, role_id)
select u.id, 'r_normal_user' from la_user u where u.role <> 'ADMIN'
and not exists (select 1 from la_user_role ur where ur.user_id = u.id);

update la_role
set permissions_json = '["DASHBOARD_VIEW","STATS_VIEW","NAMESPACE_VIEW","NAMESPACE_CREATE","NAMESPACE_UPDATE","NAMESPACE_DISABLE","NAMESPACE_DELETE","TOPIC_VIEW","TOPIC_CREATE","TOPIC_UPDATE","TOPIC_PUBLISH","TOPIC_DISABLE","TOPIC_DELETE","APIKEY_VIEW","APIKEY_CREATE","APIKEY_UPDATE","APIKEY_ROTATE","APIKEY_DELETE","CONTACT_VIEW","CONTACT_CREATE","CONTACT_UPDATE","CONTACT_DELETE","AUDIT_VIEW","DELIVERY_VIEW","DELIVERY_PAYLOAD_READ"]',
    updated_at = current_timestamp
where id = 'r_normal_user';

update la_role
set permissions_json = '["DASHBOARD_VIEW","STATS_VIEW","STATS_VIEW_ALL","NAMESPACE_VIEW","NAMESPACE_VIEW_ALL","NAMESPACE_CREATE","NAMESPACE_UPDATE","NAMESPACE_DISABLE","NAMESPACE_DELETE","TOPIC_VIEW","TOPIC_VIEW_ALL","TOPIC_CREATE","TOPIC_UPDATE","TOPIC_PUBLISH","TOPIC_DISABLE","TOPIC_DELETE","APIKEY_VIEW","APIKEY_VIEW_ALL","APIKEY_CREATE","APIKEY_UPDATE","APIKEY_ROTATE","APIKEY_DELETE","CONTACT_VIEW","CONTACT_VIEW_ALL","CONTACT_CREATE","CONTACT_UPDATE","CONTACT_DELETE","AUDIT_VIEW","AUDIT_VIEW_ALL","DELIVERY_VIEW","DELIVERY_PAYLOAD_READ","USER_VIEW","USER_CREATE","USER_UPDATE","USER_DELETE","ROLE_VIEW","ROLE_CREATE","ROLE_UPDATE","ROLE_DELETE","SYSTEM_HEALTH_VIEW","SYSTEM_SETTINGS_VIEW","SYSTEM_SETTINGS_UPDATE","MAIL_CONFIG_VIEW","MAIL_CONFIG_UPDATE","SMTP_TEST"]',
    updated_at = current_timestamp
where id = 'r_super_admin';

alter table la_user drop column role;
alter table la_user drop column permissions_json;
