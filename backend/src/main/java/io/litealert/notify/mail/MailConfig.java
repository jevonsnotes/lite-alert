package io.litealert.notify.mail;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.litealert.common.crypto.Encrypted;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * SMTP configuration that operators can edit at runtime via the admin UI.
 *
 * <p>If absent, lite-alert falls back to whatever Spring built from
 * {@code spring.mail.*} in application.yml. When present, this config wins
 * and is rebuilt into a fresh {@link org.springframework.mail.javamail.JavaMailSender}
 * inside {@link MailService}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MailConfig {

    private String host;
    private int port;
    private String username;

    /** SMTP password, Jasypt-encrypted at rest. */
    @Encrypted
    private String password;

    /** Use SMTPS (true) or STARTTLS-on-25/587 (false). */
    private boolean ssl;

    /** Optional override for the From address; defaults to {@code username}. */
    private String fromAddress;

    /** Display name shown in the From header. */
    private String fromName;

    private Instant updatedAt;
    private String updatedBy;
}
