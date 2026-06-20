package io.litealert.admin.web;

import io.litealert.admin.settings.SystemSettings;
import io.litealert.admin.settings.SystemSettingsService;
import io.litealert.auth.CurrentUser;
import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.config.LiteAlertProperties;
import io.litealert.notify.mail.MailConfig;
import io.litealert.notify.mail.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DataSource dataSource;
    private final LiteAlertProperties props;
    private final MailService mailService;
    private final SystemSettingsService settingsService;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;

    @GetMapping("/health")
    public Map<String, Object> deepHealth() {
        permissionService.require(Permissions.SYSTEM_HEALTH_VIEW);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "UP");
        r.put("time", Instant.now().toString());
        try (var c = dataSource.getConnection()) {
            r.put("database", c.getMetaData().getDatabaseProductName());
            r.put("databaseUrl", c.getMetaData().getURL());
        } catch (Exception e) {
            r.put("database", "DOWN");
            r.put("databaseError", e.getMessage());
        }
        r.put("smtpConfigured", mailService.sender().isPresent());
        r.put("smtpOverridden", mailService.isOverridden());
        return r;
    }

    @GetMapping("/mail-config")
    public Map<String, Object> getMailConfig() {
        permissionService.require(Permissions.MAIL_CONFIG_VIEW);
        return mailConfigView();
    }

    @PutMapping("/mail-config")
    public Map<String, Object> saveMailConfig(@RequestBody MailConfig req) {
        permissionService.require(Permissions.MAIL_CONFIG_UPDATE);
        mailService.save(req, currentUser.idOrThrow());
        return mailConfigView();
    }

    @DeleteMapping("/mail-config")
    public Map<String, Object> resetMailConfig() {
        permissionService.require(Permissions.MAIL_CONFIG_UPDATE);
        mailService.resetToYml(currentUser.idOrThrow());
        return mailConfigView();
    }

    @PostMapping("/smtp-test")
    public Map<String, Object> smtpTest(@RequestBody SmtpTestRequest req) {
        permissionService.require(Permissions.SMTP_TEST);
        MailService.TestResult r = mailService.sendTest(req.to(), currentUser.idOrThrow());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        if (r.error() != null) out.put("error", r.error());
        return out;
    }

    @GetMapping("/settings")
    public SystemSettings getSettings() {
        permissionService.require(Permissions.SYSTEM_SETTINGS_VIEW);
        return settingsService.current();
    }

    @PutMapping("/settings")
    public SystemSettings saveSettings(@RequestBody SystemSettings req) {
        permissionService.require(Permissions.SYSTEM_SETTINGS_UPDATE);
        return settingsService.save(req, currentUser.idOrThrow());
    }

    private Map<String, Object> mailConfigView() {
        MailConfig c = mailService.currentConfig();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("overridden", mailService.isOverridden());
        if (c == null) {
            r.put("config", null);
            return r;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("host", c.getHost());
        view.put("port", c.getPort());
        view.put("username", c.getUsername());
        view.put("hasPassword", c.getPassword() != null && !c.getPassword().isEmpty());
        view.put("ssl", c.isSsl());
        view.put("fromAddress", c.getFromAddress());
        view.put("fromName", c.getFromName());
        view.put("updatedAt", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        view.put("updatedBy", c.getUpdatedBy());
        r.put("config", view);
        return r;
    }

    public record SmtpTestRequest(String to) {}
}
