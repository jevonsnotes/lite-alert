package io.litealert.scheduler.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable outbound-webhook notification definition owned by a user. Bound to scheduled tasks
 * via {@code SchedulerTask.notifyConfigIds} (draft track) / {@code TaskConfig.Meta.notifyConfigIds}
 * (published track). The body template is rendered with task-execution variables before sending.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerNotifyConfig {

    /** When to fire the notification, relative to the task execution outcome. */
    public enum TriggerOn { SUCCESS, FAIL, ALWAYS }

    private String id;
    private String ownerId;
    private String name;
    /** HTTP method. */
    private String method;
    /** Outbound URL. */
    private String url;
    /** Custom request headers (name -> value). */
    private List<Header> headers;
    /** Raw JSON body template, rendered with execution variables. */
    private String bodyTemplate;
    private TriggerOn triggerOn;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private String name;
        private String value;
    }
}
