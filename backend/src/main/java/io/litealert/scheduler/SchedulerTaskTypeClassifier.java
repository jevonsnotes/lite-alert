package io.litealert.scheduler;

import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.scheduler.domain.SchedulerTaskType;

/**
 * Validates the task-type string at the API boundary. Only {@link SchedulerTaskType#API} is
 * supported in the first cut; the indirection is the extension point for future types.
 */
class SchedulerTaskTypeClassifier {

    SchedulerTaskType classify(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "task type is required");
        }
        try {
            return SchedulerTaskType.valueOf(taskType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "unsupported task type: " + taskType);
        }
    }
}
