package io.litealert.scheduler.domain;

import java.util.List;

/**
 * Task-type marker, persisted on {@code la_scheduler_task.task_type}. {@link Type#API} runs an
 * HTTP probe + response assertion; {@link Type#TCP} runs a TCP connectivity probe. Each maps to a
 * {@link io.litealert.scheduler.domain.TaskConfig} subclass (polymorphic JSON discriminator).
 */
public enum SchedulerTaskType {
    API,
    TCP
}
