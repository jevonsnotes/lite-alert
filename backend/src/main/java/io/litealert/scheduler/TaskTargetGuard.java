package io.litealert.scheduler;

/**
 * Guards an outbound target (host:port) before a scheduled task opens a connection (design D10).
 * The default implementation ({@link AllowAllTaskTargetGuard}) permits everything; a real
 * configurable guard is provided by the {@code add-task-target-guard} change.
 *
 * <p>Implementations MUST resolve the host and validate it; on rejection they throw a
 * {@link io.litealert.common.error.BusinessException}, which the engine treats as a failed
 * execution (call record + audit). The guard is invoked for both API (URL -> host/port) and TCP
 * ({@code host:port}) tasks before any network call.
 */
public interface TaskTargetGuard {

    /** Validate the outbound target; throw to reject. */
    void check(String host, int port);
}
