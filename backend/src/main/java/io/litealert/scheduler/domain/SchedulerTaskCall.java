package io.litealert.scheduler.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One execution record of a scheduled task (design D5). Every trigger writes exactly one
 * row, success or failure. {@code responseExcerpt} is masked/truncated before storage.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerTaskCall {

    public enum Status { SUCCESS, FAIL }

    private String id;
    private String taskId;
    private Instant triggeredAt;
    /** Protocol: {@code "API"} or {@code "TCP"}. */
    private String protocol;
    /** HTTP method, if API task. */
    private String method;
    /** Request URL, if API task. */
    private String url;
    /** TCP target {@code host:port}, if TCP task. */
    private String tcpTarget;
    private Integer httpStatus;
    /** TCP connect outcome, if TCP task; null for API. */
    private Boolean tcpOk;
    private Long durationMs;
    /** Overall success: HTTP 2xx AND assertion (if any) passed. */
    private Status status;
    /** Assertion result; null when task has no assertion (judged by HTTP status only). */
    private Boolean assertionPassed;
    private String errorMessage;
    /** Masked + truncated response body excerpt. */
    private String responseExcerpt;
    private Instant createdAt;
}
