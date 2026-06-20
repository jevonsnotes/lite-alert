package io.litealert.notify.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import io.litealert.common.crypto.Encrypted;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Generic delivery destination. The {@code type} discriminates which channel
 * picks it up; {@code endpoint} is the per-channel address (email address,
 * webhook URL, etc.) and is treated as sensitive (encrypted at rest).
 *
 * <p>For channels that need additional config (e.g. the webhook secret used
 * by 企业微信), {@code secret} carries it. Email targets leave {@code secret}
 * null. We deliberately keep the schema flat — extending later is just
 * adding more {@link Type} values.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_notify_target")
public class NotifyTarget {

    public enum Type {
        EMAIL,            // SMTP via Spring Mail
        DINGTALK,         // DingTalk custom robot webhook
        FEISHU,           // Lark (Feishu) custom webhook
        WECOM,            // 企业微信 group robot
        WEBHOOK           // Generic outbound HTTP — body composed from per-topic template
    }

    @Id(keyType = KeyType.None)
    private String id;

    @Column(value = "user_id")
    private String userId;

    @Column
    private String label;

    @Column
    private Type type;

    /** email address OR webhook URL — encrypted at rest. */
    @Encrypted
    @Column
    private String endpoint;

    /** Optional channel-specific secret (DingTalk signed mode, etc.). */
    @Encrypted
    @Column
    private String secret;

    @Column
    private boolean enabled;

    @Column(value = "created_at")
    private Instant createdAt;
}
