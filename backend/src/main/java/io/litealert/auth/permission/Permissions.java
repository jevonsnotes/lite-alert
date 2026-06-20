package io.litealert.auth.permission;

import java.util.List;

public final class Permissions {
    public static final String DASHBOARD_VIEW = "DASHBOARD_VIEW";
    public static final String STATS_VIEW = "STATS_VIEW";
    public static final String STATS_VIEW_ALL = "STATS_VIEW_ALL";
    public static final String NAMESPACE_VIEW = "NAMESPACE_VIEW";
    public static final String NAMESPACE_VIEW_ALL = "NAMESPACE_VIEW_ALL";
    public static final String NAMESPACE_CREATE = "NAMESPACE_CREATE";
    public static final String NAMESPACE_UPDATE = "NAMESPACE_UPDATE";
    public static final String NAMESPACE_DISABLE = "NAMESPACE_DISABLE";
    public static final String NAMESPACE_DELETE = "NAMESPACE_DELETE";
    public static final String TOPIC_VIEW = "TOPIC_VIEW";
    public static final String TOPIC_VIEW_ALL = "TOPIC_VIEW_ALL";
    public static final String TOPIC_CREATE = "TOPIC_CREATE";
    public static final String TOPIC_UPDATE = "TOPIC_UPDATE";
    public static final String TOPIC_PUBLISH = "TOPIC_PUBLISH";
    public static final String TOPIC_DISABLE = "TOPIC_DISABLE";
    public static final String TOPIC_DELETE = "TOPIC_DELETE";
    public static final String APIKEY_VIEW = "APIKEY_VIEW";
    public static final String APIKEY_VIEW_ALL = "APIKEY_VIEW_ALL";
    public static final String APIKEY_CREATE = "APIKEY_CREATE";
    public static final String APIKEY_UPDATE = "APIKEY_UPDATE";
    public static final String APIKEY_ROTATE = "APIKEY_ROTATE";
    public static final String APIKEY_DELETE = "APIKEY_DELETE";
    public static final String CONTACT_VIEW = "CONTACT_VIEW";
    public static final String CONTACT_VIEW_ALL = "CONTACT_VIEW_ALL";
    public static final String CONTACT_CREATE = "CONTACT_CREATE";
    public static final String CONTACT_UPDATE = "CONTACT_UPDATE";
    public static final String CONTACT_DELETE = "CONTACT_DELETE";
    public static final String AUDIT_VIEW = "AUDIT_VIEW";
    public static final String AUDIT_VIEW_ALL = "AUDIT_VIEW_ALL";
    public static final String DELIVERY_VIEW = "DELIVERY_VIEW";
    public static final String DELIVERY_PAYLOAD_READ = "DELIVERY_PAYLOAD_READ";
    public static final String USER_VIEW = "USER_VIEW";
    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String USER_DELETE = "USER_DELETE";
    public static final String ROLE_VIEW = "ROLE_VIEW";
    public static final String ROLE_CREATE = "ROLE_CREATE";
    public static final String ROLE_UPDATE = "ROLE_UPDATE";
    public static final String ROLE_DELETE = "ROLE_DELETE";
    public static final String SYSTEM_HEALTH_VIEW = "SYSTEM_HEALTH_VIEW";
    public static final String SYSTEM_SETTINGS_VIEW = "SYSTEM_SETTINGS_VIEW";
    public static final String SYSTEM_SETTINGS_UPDATE = "SYSTEM_SETTINGS_UPDATE";
    public static final String MAIL_CONFIG_VIEW = "MAIL_CONFIG_VIEW";
    public static final String MAIL_CONFIG_UPDATE = "MAIL_CONFIG_UPDATE";
    public static final String SMTP_TEST = "SMTP_TEST";

    public static final List<String> ALL = List.of(
            DASHBOARD_VIEW, STATS_VIEW, STATS_VIEW_ALL,
            NAMESPACE_VIEW, NAMESPACE_VIEW_ALL, NAMESPACE_CREATE, NAMESPACE_UPDATE, NAMESPACE_DISABLE, NAMESPACE_DELETE,
            TOPIC_VIEW, TOPIC_VIEW_ALL, TOPIC_CREATE, TOPIC_UPDATE, TOPIC_PUBLISH, TOPIC_DISABLE, TOPIC_DELETE,
            APIKEY_VIEW, APIKEY_VIEW_ALL, APIKEY_CREATE, APIKEY_UPDATE, APIKEY_ROTATE, APIKEY_DELETE,
            CONTACT_VIEW, CONTACT_VIEW_ALL, CONTACT_CREATE, CONTACT_UPDATE, CONTACT_DELETE,
            AUDIT_VIEW, AUDIT_VIEW_ALL, DELIVERY_VIEW, DELIVERY_PAYLOAD_READ,
            USER_VIEW, USER_CREATE, USER_UPDATE, USER_DELETE,
            ROLE_VIEW, ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE,
            SYSTEM_HEALTH_VIEW, SYSTEM_SETTINGS_VIEW, SYSTEM_SETTINGS_UPDATE,
            MAIL_CONFIG_VIEW, MAIL_CONFIG_UPDATE, SMTP_TEST
    );

    private Permissions() {}
}
