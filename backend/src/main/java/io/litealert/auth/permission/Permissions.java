package io.litealert.auth.permission;

import java.util.List;

public final class Permissions {
    public static final String DELIVERY_PAYLOAD_READ = "DELIVERY_PAYLOAD_READ";
    public static final String ROLE_VIEW = "ROLE_VIEW";
    public static final String ROLE_CREATE = "ROLE_CREATE";
    public static final String ROLE_UPDATE = "ROLE_UPDATE";
    public static final String ROLE_DELETE = "ROLE_DELETE";
    public static final String USER_VIEW = "USER_VIEW";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String SYSTEM_SETTINGS_VIEW = "SYSTEM_SETTINGS_VIEW";
    public static final String SYSTEM_SETTINGS_UPDATE = "SYSTEM_SETTINGS_UPDATE";

    public static final List<String> ALL = List.of(
            "DASHBOARD_VIEW", "NAMESPACE_VIEW", "NAMESPACE_CREATE", "NAMESPACE_DISABLE", "NAMESPACE_DELETE",
            "TOPIC_VIEW", "TOPIC_CREATE", "TOPIC_UPDATE", "TOPIC_PUBLISH", "TOPIC_DISABLE", "TOPIC_DELETE",
            "APIKEY_VIEW", "APIKEY_CREATE", "APIKEY_UPDATE", "APIKEY_ROTATE", "APIKEY_DELETE",
            "CONTACT_VIEW", "CONTACT_CREATE", "CONTACT_UPDATE", "CONTACT_DELETE",
            "AUDIT_VIEW", "DELIVERY_VIEW", DELIVERY_PAYLOAD_READ,
            USER_VIEW, "USER_CREATE", USER_UPDATE, "USER_DELETE",
            ROLE_VIEW, ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE,
            "SYSTEM_SETTINGS_VIEW", SYSTEM_SETTINGS_UPDATE
    );

    private Permissions() {}
}
