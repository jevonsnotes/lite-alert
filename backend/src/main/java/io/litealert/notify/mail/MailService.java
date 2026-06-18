package io.litealert.notify.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.db.DbJson;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private static final String CONFIG_ID = "mail-config";

    private final JdbcTemplate jdbc;
    private final DbJson json;
    private final AuditLogger audit;

    @Autowired(required = false)
    private JavaMailSender bootstrapSender;

    private final AtomicReference<MailConfig> configRef = new AtomicReference<>();
    private final AtomicReference<JavaMailSender> senderRef = new AtomicReference<>();

    @Value("${spring.mail.host:}")
    private String ymlHost;

    @PostConstruct
    void init() {
        try {
            String storedJson = jdbc.query("select settings_json from la_system_settings where id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, CONFIG_ID);
            MailConfig stored = json.read(storedJson, new TypeReference<>() {}, null);
            if (stored != null && stored.getHost() != null && !stored.getHost().isBlank()) {
                normalizeFrom(stored);
                configRef.set(stored);
                senderRef.set(buildSender(stored));
                log.info("MailService loaded SMTP config from database (host={})", stored.getHost());
                return;
            }
        } catch (Exception ignored) {
            // schema may not be initialized yet; yml fallback below still works.
        }
        if (bootstrapSender != null && ymlHost != null && !ymlHost.isBlank()) {
            senderRef.set(bootstrapSender);
            log.info("MailService using Spring-auto-configured sender (host={})", ymlHost);
        } else {
            log.info("MailService: no SMTP configured");
        }
    }

    public Optional<JavaMailSender> sender() { return Optional.ofNullable(senderRef.get()); }

    public MailConfig currentConfig() {
        MailConfig c = configRef.get();
        if (c != null) return c;
        if (ymlHost != null && !ymlHost.isBlank()) return MailConfig.builder().host(ymlHost).port(0).build();
        return null;
    }

    public boolean isOverridden() { return configRef.get() != null; }

    public synchronized MailConfig save(MailConfig incoming, String actor) {
        if (incoming == null || incoming.getHost() == null || incoming.getHost().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "SMTP host is required");
        }
        if (incoming.getPort() <= 0 || incoming.getPort() > 65535) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "SMTP port out of range");
        }
        MailConfig prev = configRef.get();
        if ((incoming.getPassword() == null || incoming.getPassword().isEmpty()) && prev != null) {
            incoming.setPassword(prev.getPassword());
        }
        normalizeFrom(incoming);
        incoming.setUpdatedAt(Instant.now());
        incoming.setUpdatedBy(actor);

        upsertConfig(incoming);
        configRef.set(incoming);
        senderRef.set(buildSender(incoming));
        audit.log("mail.config.update", Map.of("actor", actor, "host", incoming.getHost()));
        return incoming;
    }

    public synchronized void resetToYml(String actor) {
        jdbc.update("delete from la_system_settings where id = ?", CONFIG_ID);
        configRef.set(null);
        senderRef.set(bootstrapSender);
        audit.log("mail.config.reset", Map.of("actor", actor));
    }

    public TestResult sendTest(String to, String actor) {
        JavaMailSender s = senderRef.get();
        if (s == null) return new TestResult(false, "SMTP not configured");
        try {
            var msg = s.createMimeMessage();
            var h = new org.springframework.mail.javamail.MimeMessageHelper(msg, "UTF-8");
            h.setTo(to);
            MailConfig c = configRef.get();
            String from = fromAddress(c);
            if (from != null) {
                if (c != null && c.getFromName() != null && !c.getFromName().isBlank()) h.setFrom(from, c.getFromName());
                else h.setFrom(from);
            }
            h.setSubject("[lite-alert] SMTP 测试");
            h.setText("如果你能看到这封邮件，说明 SMTP 配置正确。— lite-alert", false);
            s.send(msg);
            audit.log("mail.test.success", Map.of("actor", actor, "to", to));
            return new TestResult(true, null);
        } catch (Exception e) {
            audit.log("mail.test.failed", Map.of("actor", actor, "to", to, "error", String.valueOf(e.getMessage())));
            return new TestResult(false, e.getMessage());
        }
    }

    private void upsertConfig(MailConfig incoming) {
        boolean exists = Boolean.TRUE.equals(jdbc.query("select count(*) from la_system_settings where id = ?",
                rs -> rs.next() && rs.getInt(1) > 0, CONFIG_ID));
        if (exists) jdbc.update("update la_system_settings set settings_json=?, updated_at=? where id=?",
                json.write(incoming), Timestamp.from(Instant.now()), CONFIG_ID);
        else jdbc.update("insert into la_system_settings(id, settings_json, updated_at) values (?, ?, ?)",
                CONFIG_ID, json.write(incoming), Timestamp.from(Instant.now()));
    }

    private void normalizeFrom(MailConfig c) {
        if (c == null || c.getUsername() == null || c.getUsername().isBlank()) return;
        c.setFromAddress(c.getUsername());
    }

    private String fromAddress(MailConfig c) {
        if (c == null) return null;
        if (c.getFromAddress() != null && !c.getFromAddress().isBlank()) return c.getFromAddress();
        if (c.getUsername() != null && !c.getUsername().isBlank()) return c.getUsername();
        return null;
    }

    private JavaMailSender buildSender(MailConfig c) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(c.getHost());
        impl.setPort(c.getPort());
        impl.setUsername(c.getUsername());
        impl.setPassword(c.getPassword());
        Properties p = impl.getJavaMailProperties();
        p.put("mail.smtp.auth", c.getUsername() != null && !c.getUsername().isBlank());
        p.put("mail.smtp.ssl.enable", String.valueOf(c.isSsl()));
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "10000");
        return impl;
    }

    public record TestResult(boolean ok, String error) {}
}
