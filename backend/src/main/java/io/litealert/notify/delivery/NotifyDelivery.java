package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.common.util.IdGenerator;
import io.litealert.notify.domain.NotifyTarget;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NotifyDelivery {

    public enum Status { PENDING, SENDING, RETRY_WAIT, SENT, GIVE_UP, CANCELLED }

    private String id;
    private String traceId;
    private String topicId;
    private String targetId;
    private NotifyTarget.Type channel;
    private String payloadJson;
    private Status status;
    private int attempt;
    private int maxAttempts;
    private Instant nextRetryAt;
    private String lockedBy;
    private Instant lockedAt;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant finishedAt;

    public static NotifyDelivery pending(String traceId, String topicId, String targetId,
                                         NotifyTarget.Type channel, JsonNode payload) {
        Instant now = Instant.now();
        return NotifyDelivery.builder()
                .id(IdGenerator.entityId("del"))
                .traceId(traceId)
                .topicId(topicId)
                .targetId(targetId)
                .channel(channel)
                .payloadJson(payload == null ? "null" : payload.toString())
                .status(Status.PENDING)
                .attempt(0)
                .maxAttempts(5)
                .nextRetryAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
