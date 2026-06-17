package io.litealert.common.web;

/**
 * Per-request trace id. Populated by {@link TraceIdFilter} and read by
 * exception handlers / audit logger.
 */
public final class TraceIdHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceIdHolder() {}

    public static void set(String id) {
        HOLDER.set(id);
    }

    public static String current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
