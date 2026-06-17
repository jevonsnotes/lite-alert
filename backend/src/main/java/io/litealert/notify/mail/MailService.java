package io.litealert.notify.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.storage.FileStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single source of truth for "which JavaMailSender do we currently use".
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If {@code mail-config.json} exists on disk, build a sender from it.</li>
 *   <li>Otherwise, use the Spring-auto-configured bean (if any) — the one
 *       defined by {@code spring.mail.*} in application.yml.</li>
 *   <li>If neither is present, sends become no-ops.</li>
 * </ol>
 *
 * <p>The active sender is held in an {@link AtomicReference} so callers always
 * see the latest config without holding any lock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    public static final String FILE = "mail-config.json";

    private final FileStore fileStore;
    private final AuditLogger audit;

    @Autowired(required = false)
    private JavaMailSender bootstrapSender;

    /** Snapshot of the persisted config; null when only yml is in use. */
    private final AtomicReference<MailConfig> configRef = new AtomicReference<>();
    /** Active sender, swapped on save / boot. */
    private final AtomicReference<JavaMailSender> senderRef = new AtomicReference<>();

    @Value("${spring.mail.host:}")
    private String ymlHost;

    @PostConstruct
    void init() {
        MailConfig stored = fileStore.readJson(FILE, new TypeReference<>() {}, null);
        if (stored != null && stored.getHost() != null && !stored.getHost().isBlank()) {
            configRef.set(stored);
            senderRef.set(buildSender(stored));
            log.info("MailService loaded SMTP config from {} (host={})", FILE, stored.getHost());
        } else if (bootstrapSender != null && ymlHost != null && !ymlHost.isBlank()) {
            senderRef.set(bootstrapSender);
            log.info("MailService using Spring-auto-configured sender (host={})", ymlHost);
        } else {
            log.info("MailService: no SMTP configured");
        }
    }

    /** May be empty when SMTP isn't configured yet — channels handle that. */
    public Optional<JavaMailSender> sender() {
        return Optional.ofNullable(senderRef.get());
    }

    /** Returns the editable config; null means "no override, falling back to yml". */
    public MailConfig currentConfig() {
        MailConfig c = configRef.get();
        if (c != null) return c;
        // surface a read-only mirror of yml so the UI shows what's running
        if (ymlHost != null && !ymlHost.isBlank()) {
            return MailConfig.builder().host(ymlHost).port(0).build();
        }
        return null;
    }

    /** Returns true iff the current config originated from on-disk override. */
    public boolean isOverridden() {
        return configRef.get() != null;
    }

    public synchronized MailConfig save(MailConfig incoming, String actor) {
        if (incoming == null || incoming.getHost() == null || incoming.getHost().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "SMTP host is required");
        }
        if (incoming.getPort() <= 0 || incoming.getPort() > 65535) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "SMTP port out of range");
        }
        // preserve old password if the request omitted it (UI sends "" to keep)
        MailConfig prev = configRef.get();
        if ((incoming.getPassword() == null || incoming.getPassword().isEmpty()) && prev != null) {
            incoming.setPassword(prev.getPassword());
        }
        incoming.setUpdatedAt(Instant.now());
        incoming.setUpdatedBy(actor);

        fileStore.writeJson(FILE, incoming);
        configRef.set(incoming);
        senderRef.set(buildSender(incoming));
        audit.log("mail.config.update", Map.of("actor", actor, "host", incoming.getHost()));
        return incoming;
    }

    public synchronized void resetToYml(String actor) {
        fileStore.delete(FILE);
        configRef.set(null);
        senderRef.set(bootstrapSender);   // may be null; that's fine
        audit.log("mail.config.reset", Map.of("actor", actor));
    }

    /**
     * Sends a single test email synchronously. Returns the SMTP error message
     * on failure so the UI can show the user something actionable.
     */
    public TestResult sendTest(String to, String actor) {
        JavaMailSender s = senderRef.get();
        if (s == null) {
            return new TestResult(false, "SMTP not configured");
        }
        try {
            var msg = s.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
            h.setTo(to);
            MailConfig c = configRef.get();
            if (c != null && c.getFromAddress() != null && !c.getFromAddress().isBlank()) {
                if (c.getFromName() != null && !c.getFromName().isBlank()) {
                    h.setFrom(c.getFromAddress(), c.getFromName());
                } else {
                    h.setFrom(c.getFromAddress());
                }
            }
            h.setSubject("[lite-alert] SMTP 测试");
            h.setText("如果你能看到这封邮件，说明 SMTP 配置正确。— lite-alert", false);
            s.send(msg);
            audit.log("mail.test.success", Map.of("actor", actor, "to", to));
            return new TestResult(true, null);
        } catch (Exception e) {
            audit.log("mail.test.failed", Map.of("actor", actor, "to", to,
                    "error", String.valueOf(e.getMessage())));
            return new TestResult(false, e.getMessage());
        }
    }

    private JavaMailSender buildSender(MailConfig c) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(c.getHost());
        impl.setPort(c.getPort());
        impl.setUsername(c.getUsername());
        impl.setPassword(c.getPassword());
        impl.setDefaultEncoding("UTF-8");

        Properties p = impl.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", c.getUsername() != null && !c.getUsername().isBlank());
        if (c.isSsl()) {
            p.put("mail.smtp.ssl.enable", "true");
        } else {
            p.put("mail.smtp.starttls.enable", "true");
        }
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        return impl;
    }

    public record TestResult(boolean ok, String error) {}
}
