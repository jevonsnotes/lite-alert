package io.litealert.scheduler;

import org.springframework.stereotype.Component;

/**
 * Default {@link TaskTargetGuard} that permits every outbound target (design D10).
 *
 * <p>{@link #check} is a no-op, so with only this bean present all targets pass. The
 * {@code add-task-target-guard} change provides a real {@link CidrTaskTargetGuard} annotated
 * {@link org.springframework.context.annotation.Primary @Primary}, which makes it win the autowire
 * candidate over this default - so this bean becomes dormant (never injected) once the real guard
 * is on the classpath, with zero wiring change.
 */
@Component
public class AllowAllTaskTargetGuard implements TaskTargetGuard {

    @Override
    public void check(String host, int port) {
        // no-op: permit all targets
    }
}
