package io.litealert.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.audit.AuditLogger;
import io.litealert.common.db.DbJson;
import io.litealert.notify.channel.WebhookResponseAssertor;
import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.TaskConfig;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskCall;
import io.litealert.scheduler.domain.SchedulerTaskCallStore;
import io.litealert.scheduler.domain.SchedulerTaskStore;
import io.litealert.scheduler.domain.SchedulerTaskType;
import io.litealert.scheduler.domain.SchedulerNotifyConfig;
import io.litealert.scheduler.domain.SchedulerNotifyConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerEngineTest {

    private SchedulerTaskStore taskStore;
    private SchedulerTaskCallStore callStore;
    private SchedulerEngine engine;
    private FakeHttp http;
    private SchedulerNotifyConfigStore notifyStore;
    private io.litealert.admin.settings.SystemSettingsService settingsService;
    private AuditLogger audit;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:scheduler_engine_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        DbJson json = new DbJson(new ObjectMapper().findAndRegisterModules());
        jdbc.execute("drop table if exists la_scheduler_notify_config");
        jdbc.execute("drop table if exists la_scheduler_task_call");
        jdbc.execute("drop table if exists la_scheduler_task");
        jdbc.execute("drop table if exists la_audit_log");
        jdbc.execute("drop table if exists la_system_settings");
        jdbc.execute("create table la_scheduler_task (" +
                "id varchar(64) primary key, owner_id varchar(64), name varchar(128), description varchar(500), " +
                "task_type varchar(16), cron varchar(128), enabled boolean, status varchar(16), " +
                "draft_config_json clob, published_config_json clob, published_at timestamp, " +
                "notify_config_ids_json clob, created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table la_scheduler_task_call (" +
                "id varchar(64) primary key, task_id varchar(64), triggered_at timestamp, protocol varchar(8), " +
                "method varchar(16), url varchar(1024), tcp_target varchar(256), http_status int, tcp_ok boolean, " +
                "duration_ms bigint, success boolean, assertion_passed boolean, " +
                "error_message clob, response_excerpt clob, created_at timestamp)");
        jdbc.execute("create table la_scheduler_notify_config (" +
                "id varchar(64) primary key, owner_id varchar(64), name varchar(128), method varchar(16), " +
                "url varchar(1024), headers_json clob, body_template clob, trigger_on varchar(16), " +
                "enabled boolean, created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table la_audit_log (id bigint auto_increment primary key, ts timestamp, type varchar(128), " +
                "actor varchar(64), trace_id varchar(128), topic_id varchar(64), api_key_id varchar(64), attrs_json clob)");
        jdbc.execute("create table la_system_settings (id varchar(64) primary key, settings_json clob, updated_at timestamp)");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        notifyStore = new SchedulerNotifyConfigStore(jdbc, json);
        taskStore = new SchedulerTaskStore(jdbc, json);
        callStore = new SchedulerTaskCallStore(jdbc);
        http = new FakeHttp();
        audit = new AuditLogger(jdbc, json);
        SchedulerNotifier notifier = new SchedulerNotifier(new io.litealert.notify.template.TemplateRenderer(mapper), http, mapper);
        ApiTaskTcpExecutor tcpExecutor = new ApiTaskTcpExecutor();
        // real guard wired against an in-memory settings service so guard rejection can be exercised
        settingsService = new io.litealert.admin.settings.SystemSettingsService(jdbc, json, audit);
        settingsService.load();
        TaskTargetGuard guard = new CidrTaskTargetGuard(settingsService);
        engine = new SchedulerEngine(taskStore, callStore, http, tcpExecutor, guard,
                new WebhookResponseAssertor(),
                audit, notifier, notifyStore);
        engine.init();
    }

    private SchedulerTask publishedTask(String id, String url, ApiTaskConfig.Assertion assertion) {
        ApiTaskConfig cfg = new ApiTaskConfig();
        cfg.setMethod("GET");
        cfg.setUrl(url);
        cfg.setAssertion(assertion);
        SchedulerTask t = SchedulerTask.builder()
                .id(id).ownerId("u_1").name("probe").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.PUBLISHED)
                .draftConfig(cfg).publishedConfig(cfg).publishedAt(Instant.now())
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        taskStore.save(t);
        return t;
    }

    @Test
    void publishSchedulesTaskAndRunWritesSuccessRecord() {
        SchedulerTask t = publishedTask("st_ok", "https://ok.example", null);
        http.respond(200, "application/json", "{\"ok\":true}");

        engine.reschedule(t.getId());
        engine.run(t.getId());

        List<SchedulerTaskCall> calls = callStore.findByTask(t.getId(), null, null, 10);
        assertThat(calls).hasSize(1);
        SchedulerTaskCall c = calls.get(0);
        assertThat(c.getStatus()).isEqualTo(SchedulerTaskCall.Status.SUCCESS);
        assertThat(c.getHttpStatus()).isEqualTo(200);
        assertThat(c.getMethod()).isEqualTo("GET");
        assertThat(c.getAssertionPassed()).isNull();
        assertThat(c.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void republishHotSwapsConfig() {
        SchedulerTask t = publishedTask("st_swap", "https://old.example", null);
        http.respond(200, "application/json", "{}");
        engine.reschedule(t.getId());

        // edit draft + republish with a new URL
        ApiTaskConfig newCfg = new ApiTaskConfig();
        newCfg.setMethod("GET");
        newCfg.setUrl("https://new.example");
        t.setDraftConfig(newCfg);
        t.setPublishedConfig(newCfg);
        taskStore.save(t);
        engine.reschedule(t.getId()); // hot update

        engine.run(t.getId());

        SchedulerTaskCall c = callStore.findByTask(t.getId(), null, null, 10).get(0);
        assertThat(c.getUrl()).isEqualTo("https://new.example");
    }

    @Test
    void disableUnschedulesAndInFlightRunIsSkipped() {
        SchedulerTask t = publishedTask("st_dis", "https://dis.example", null);
        http.respond(200, "application/json", "{}");
        engine.reschedule(t.getId());

        t.setStatus(SchedulerTask.Status.DISABLED);
        taskStore.save(t);
        engine.unschedule(t.getId());

        engine.run(t.getId()); // no longer schedulable → skipped, no record
        assertThat(callStore.findByTask(t.getId(), null, null, 10)).isEmpty();
    }

    @Test
    void failedAssertionRecordsFailWithMessage() {
        ApiTaskConfig.Assertion assertion = new ApiTaskConfig.Assertion();
        assertion.setLogic(ApiTaskConfig.Assertion.Logic.AND);
        ApiTaskConfig.Condition cond = new ApiTaskConfig.Condition();
        cond.setPath("$.code");
        cond.setOperator(ApiTaskConfig.Condition.Operator.EQ);
        cond.setExpected("0");
        assertion.setConditions(List.of(cond));
        SchedulerTask t = publishedTask("st_assert", "https://assert.example", assertion);
        http.respond(200, "application/json", "{\"code\":1}");

        engine.reschedule(t.getId());
        engine.run(t.getId());

        SchedulerTaskCall c = callStore.findByTask(t.getId(), null, null, 10).get(0);
        assertThat(c.getStatus()).isEqualTo(SchedulerTaskCall.Status.FAIL);
        assertThat(c.getAssertionPassed()).isFalse();
        assertThat(c.getErrorMessage()).contains("$.code");
    }

    @Test
    void startupRecoversPublishedTasks() {
        // a published task already in the store before engine.init()
        publishedTask("st_recover", "https://recover.example", null);
        http.respond(200, "application/json", "{}");

        engine.init(); // rebuild schedules
        engine.run("st_recover");

        assertThat(callStore.findByTask("st_recover", null, null, 10)).hasSize(1);
    }

    @Test
    void tcpProbeToOpenPortWritesSuccessRecord() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            SchedulerTask t = publishedTcpTask("st_tcp_ok", "127.0.0.1", server.getLocalPort());
            engine.run("st_tcp_ok");

            SchedulerTaskCall c = callStore.findByTask("st_tcp_ok", null, null, 10).get(0);
            assertThat(c.getProtocol()).isEqualTo("TCP");
            assertThat(c.getTcpOk()).isTrue();
            assertThat(c.getStatus()).isEqualTo(SchedulerTaskCall.Status.SUCCESS);
            assertThat(c.getAssertionPassed()).isNull();
            assertThat(c.getResponseExcerpt()).startsWith("connected to 127.0.0.1:");
        }
    }

    @Test
    void tcpProbeToClosedPortWritesFailureRecord() throws Exception {
        int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { port = s.getLocalPort(); }

        publishedTcpTask("st_tcp_fail", "127.0.0.1", port);
        engine.run("st_tcp_fail");

        SchedulerTaskCall c = callStore.findByTask("st_tcp_fail", null, null, 10).get(0);
        assertThat(c.getProtocol()).isEqualTo("TCP");
        assertThat(c.getTcpOk()).isFalse();
        assertThat(c.getStatus()).isEqualTo(SchedulerTaskCall.Status.FAIL);
        assertThat(c.getErrorMessage()).isNotNull();
    }

    private SchedulerTask publishedTcpTask(String id, String host, int port) {
        io.litealert.scheduler.domain.TcpTaskConfig cfg = new io.litealert.scheduler.domain.TcpTaskConfig();
        cfg.setHost(host);
        cfg.setPort(port);
        SchedulerTask t = SchedulerTask.builder()
                .id(id).ownerId("u_1").name("tcp-probe").taskType(SchedulerTaskType.TCP)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.PUBLISHED)
                .draftConfig(cfg).publishedConfig(cfg).publishedAt(Instant.now())
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        taskStore.save(t);
        return t;
    }

    @Test
    void tcpProbeToBlockedTargetWritesFailureAndAudit() {
        // enable the guard with default blocked private ranges, then probe a private IP -> blocked
        io.litealert.admin.settings.SystemSettings s = new io.litealert.admin.settings.SystemSettings();
        s.getTaskTargetGuard().setEnabled(true);
        settingsService.save(s, "tester");

        publishedTcpTask("st_tcp_block", "10.0.0.5", 3306);
        engine.run("st_tcp_block");

        SchedulerTaskCall c = callStore.findByTask("st_tcp_block", null, null, 10).get(0);
        assertThat(c.getProtocol()).isEqualTo("TCP");
        assertThat(c.getStatus()).isEqualTo(SchedulerTaskCall.Status.FAIL);
        assertThat(c.getErrorMessage()).contains("被拦截");
        // target-blocked audit row written
        assertThat(jdbc.queryForObject(
                "select count(*) from la_audit_log where type = 'scheduler.task.target-blocked'",
                Integer.class)).isGreaterThan(0);
    }

    @Test
    void tcpProbeBlockedTargetThenAllowedCidrSucceeds() throws Exception {
        // first block a private IP
        io.litealert.admin.settings.SystemSettings s = new io.litealert.admin.settings.SystemSettings();
        s.getTaskTargetGuard().setEnabled(true);
        settingsService.save(s, "tester");

        // open a local listener and allow its exact IP so the guard lets it through
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            String host = server.getInetAddress().getHostAddress(); // 127.0.0.1 normally, but use resolved
            int port = server.getLocalPort();
            // 127.0.0.0/8 is in the default blocked list -> allow it explicitly
            io.litealert.admin.settings.SystemSettings s2 = new io.litealert.admin.settings.SystemSettings();
            s2.getTaskTargetGuard().setEnabled(true);
            s2.getTaskTargetGuard().setAllowedCidrs(java.util.List.of("127.0.0.0/8"));
            settingsService.save(s2, "tester");

            publishedTcpTask("st_tcp_allow", "127.0.0.1", port);
            engine.run("st_tcp_allow");

            SchedulerTaskCall c = callStore.findByTask("st_tcp_allow", null, null, 10).get(0);
            assertThat(c.getStatus()).isEqualTo(SchedulerTaskCall.Status.SUCCESS);
            assertThat(c.getTcpOk()).isTrue();
        }
    }

    /** Fake HTTP executor: returns a preset response, no network. */
    static class FakeHttp extends ApiTaskHttpExecutor {
        private ApiTaskHttpExecutor.Response response;
        int executeCount = 0;
        ApiTaskConfig lastSent;
        void respond(int status, String contentType, String body) {
            this.response = new Response(status, contentType, body);
        }
        @Override
        public Response execute(ApiTaskConfig config) {
            if (response == null) throw new IllegalStateException("no fake response set");
            executeCount++;
            lastSent = config;
            return response;
        }
    }

    private SchedulerNotifyConfig makeNotify(String id, SchedulerNotifyConfig.TriggerOn on) {
        SchedulerNotifyConfig c = SchedulerNotifyConfig.builder()
                .id(id).ownerId("u_1").name("n").method("POST").url("https://hook.example")
                .bodyTemplate("{\"text\":\"{{taskName}} {{status}}\"}").triggerOn(on).enabled(true).build();
        notifyStore.save(c);
        return c;
    }

    @Test
    void notifyFiresOnFailureForFailConfig() {
        ApiTaskConfig.Assertion assertion = new ApiTaskConfig.Assertion();
        assertion.setLogic(ApiTaskConfig.Assertion.Logic.AND);
        ApiTaskConfig.Condition cond = new ApiTaskConfig.Condition();
        cond.setPath("$.code"); cond.setOperator(ApiTaskConfig.Condition.Operator.EQ); cond.setExpected("0");
        assertion.setConditions(List.of(cond));
        SchedulerTask t = publishedTask("st_notify_fail", "https://x.example", assertion);
        // bind a FAIL notify config to the published track via meta
        makeNotify("sn_fail", SchedulerNotifyConfig.TriggerOn.FAIL);
        t.getPublishedConfig().setMeta(new TaskConfig.Meta(t.getName(), null, t.getCron()));
        t.getPublishedConfig().getMeta().setNotifyConfigIds(List.of("sn_fail"));
        taskStore.save(t);

        http.respond(200, "application/json", "{\"code\":1}"); // assertion fails → task FAIL
        int before = http.executeCount;
        engine.run("st_notify_fail");

        // 1 task call + 1 notify = 2 executes; the notify url is the hook and body is rendered
        assertThat(http.executeCount - before).isEqualTo(2);
        assertThat(http.lastSent.getUrl()).isEqualTo("https://hook.example");
        assertThat(http.lastSent.getBody().getRawText()).contains("probe FAIL");
    }

    @Test
    void notifyDoesNotFireForSuccessOnlyConfigOnFailure() {
        SchedulerTask t = publishedTask("st_notify_succ", "https://ok.example", null);
        makeNotify("sn_succ", SchedulerNotifyConfig.TriggerOn.SUCCESS);
        t.getPublishedConfig().setMeta(new TaskConfig.Meta(t.getName(), null, t.getCron()));
        t.getPublishedConfig().getMeta().setNotifyConfigIds(List.of("sn_succ"));
        taskStore.save(t);

        http.respond(200, "application/json", "{}"); // task SUCCESS
        int before = http.executeCount;
        engine.run("st_notify_succ");

        // SUCCESS config fires on success → 1 task call + 1 notify = 2 executes
        assertThat(http.executeCount - before).isEqualTo(2);
    }

    @Test
    void notifyNotSentWhenNoBindings() {
        publishedTask("st_nonotify", "https://ok.example", null);
        http.respond(200, "application/json", "{}");
        int before = http.executeCount;
        engine.run("st_nonotify");
        assertThat(http.executeCount - before).isEqualTo(1); // only the task call, no notify
    }

    @Test
    void disabledNotifyNotDispatchedButBindingPreserved() {
        SchedulerTask t = publishedTask("st_disabled_notify", "https://ok.example", null);
        // bind an ALWAYS notify config but disable it
        SchedulerNotifyConfig c = makeNotify("sn_disabled", SchedulerNotifyConfig.TriggerOn.ALWAYS);
        c.setEnabled(false);
        notifyStore.save(c);
        t.getPublishedConfig().setMeta(new TaskConfig.Meta(t.getName(), null, t.getCron()));
        t.getPublishedConfig().getMeta().setNotifyConfigIds(List.of("sn_disabled"));
        taskStore.save(t);

        http.respond(200, "application/json", "{}");
        int before = http.executeCount;
        engine.run("st_disabled_notify");

        // disabled config not dispatched: only the task call (1), no notify
        assertThat(http.executeCount - before).isEqualTo(1);
        // binding preserved: published meta still references the disabled config id
        SchedulerTask reloaded = taskStore.findById("st_disabled_notify").orElseThrow();
        assertThat(reloaded.getPublishedConfig().getMeta().getNotifyConfigIds()).contains("sn_disabled");
        // and the config is still in the store (not deleted)
        assertThat(notifyStore.findById("sn_disabled")).isPresent();

        // re-enable -> dispatch resumes
        c.setEnabled(true);
        notifyStore.save(c);
        int before2 = http.executeCount;
        engine.run("st_disabled_notify");
        assertThat(http.executeCount - before2).isEqualTo(2); // task call + notify
    }
}
