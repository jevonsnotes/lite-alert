package io.litealert.admin.settings;

import io.litealert.common.audit.AuditLogger;
import io.litealert.common.db.DbJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTargetGuardConfigNormalizeTest {

    private SystemSettingsService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:guard_settings_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table if not exists la_system_settings(id varchar(64) primary key, settings_json clob, updated_at timestamp)");
        service = new SystemSettingsService(jdbc, new DbJson(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                org.mockito.Mockito.mock(AuditLogger.class));
    }

    @Test
    void defaultConfigIsDisabledWithBuiltinCidrs() {
        SystemSettings s = new SystemSettings();
        service.normalize(s);
        assertThat(s.getTaskTargetGuard().isEnabled()).isFalse();
        assertThat(s.getTaskTargetGuard().getBlockedCidrs()).contains("10.0.0.0/8", "169.254.0.0/16");
    }

    @Test
    void invalidCidrsDroppedWithoutBlockingSave() {
        SystemSettings s = new SystemSettings();
        s.getTaskTargetGuard().setEnabled(true);
        s.getTaskTargetGuard().setBlockedCidrs(List.of("10.0.0.0/8", "not-a-cidr", "10.0.0.0/33", " 192.168.0.0/16 "));
        service.normalize(s);
        assertThat(s.getTaskTargetGuard().getBlockedCidrs()).containsExactly("10.0.0.0/8", "192.168.0.0/16");
    }

    @Test
    void invalidDomainSuffixesDropped() {
        SystemSettings s = new SystemSettings();
        s.getTaskTargetGuard().setEnabled(true);
        s.getTaskTargetGuard().setBlockedDomains(List.of("internal.corp", "", "has space.corp", ".bad.example"));
        service.normalize(s);
        assertThat(s.getTaskTargetGuard().getBlockedDomains()).containsExactly("internal.corp", "bad.example");
    }

    @Test
    void nullGuardFallsBackToDefault() {
        SystemSettings s = new SystemSettings();
        s.setTaskTargetGuard(null);
        service.normalize(s);
        assertThat(s.getTaskTargetGuard()).isNotNull();
        assertThat(s.getTaskTargetGuard().isEnabled()).isFalse();
        assertThat(s.getTaskTargetGuard().getBlockedCidrs()).isNotEmpty();
    }

    @Test
    void savePersistsAndRehydratesGuardConfig() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:guard_settings_persist;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table if not exists la_system_settings(id varchar(64) primary key, settings_json clob, updated_at timestamp)");
        DbJson json = new DbJson(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        SystemSettingsService svc = new SystemSettingsService(jdbc, json, org.mockito.Mockito.mock(AuditLogger.class));

        SystemSettings s = new SystemSettings();
        s.getTaskTargetGuard().setEnabled(true);
        s.getTaskTargetGuard().setAllowedCidrs(List.of("10.0.0.5/32"));
        s.getTaskTargetGuard().setBlockedDomains(List.of("internal.corp"));
        svc.save(s, "admin");

        // a fresh service instance rehydrates from the DB
        SystemSettingsService reloaded = new SystemSettingsService(jdbc, json, org.mockito.Mockito.mock(AuditLogger.class));
        reloaded.load();
        assertThat(reloaded.current().getTaskTargetGuard().isEnabled()).isTrue();
        assertThat(reloaded.current().getTaskTargetGuard().getAllowedCidrs()).contains("10.0.0.5/32");
        assertThat(reloaded.current().getTaskTargetGuard().getBlockedDomains()).contains("internal.corp");
    }
}
