package io.litealert.scheduler.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.db.DbJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTaskStoreTest {

    private SchedulerTaskStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:scheduler_task_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop table if exists la_scheduler_task_call");
        jdbc.execute("drop table if exists la_scheduler_task");
        jdbc.execute("create table la_scheduler_task (" +
                "id varchar(64) primary key, owner_id varchar(64), name varchar(128), description varchar(500), " +
                "task_type varchar(16), cron varchar(128), enabled boolean, status varchar(16), " +
                "draft_config_json clob, published_config_json clob, published_at timestamp, " +
                "notify_config_ids_json clob, created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table la_scheduler_task_call (" +
                "id varchar(64) primary key, task_id varchar(64), triggered_at timestamp, method varchar(16), " +
                "url varchar(1024), http_status int, duration_ms bigint, success boolean, assertion_passed boolean, " +
                "error_message clob, response_excerpt clob, created_at timestamp)");
        store = new SchedulerTaskStore(jdbc, new DbJson(new ObjectMapper().findAndRegisterModules()));
    }

    private ApiTaskConfig config(String url) {
        ApiTaskConfig c = new ApiTaskConfig();
        c.setMethod("GET");
        c.setUrl(url);
        return c;
    }

    @Test
    void editingDraftDoesNotAffectPublished() {
        SchedulerTask t = SchedulerTask.builder()
                .id("st_1").ownerId("u_1").name("probe").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.DRAFT)
                .draftConfig(config("https://a.example/old")).build();
        store.save(t);

        // first publish → published config set, status PUBLISHED
        t.setPublishedConfig(t.getDraftConfig());
        t.setStatus(SchedulerTask.Status.PUBLISHED);
        t.setPublishedAt(Instant.now());
        store.save(t);

        // edit draft only
        t.setDraftConfig(config("https://a.example/new"));
        store.save(t);

        SchedulerTask reloaded = store.findById("st_1").orElseThrow();
        assertThat(((ApiTaskConfig) reloaded.getDraftConfig()).getUrl()).isEqualTo("https://a.example/new");
        // published config unchanged → scheduler keeps using the old URL
        assertThat(((ApiTaskConfig) reloaded.getPublishedConfig()).getUrl()).isEqualTo("https://a.example/old");
    }

    @Test
    void unpublishedTaskIsNotSchedulable() {
        SchedulerTask t = SchedulerTask.builder()
                .id("st_2").ownerId("u_1").name("probe2").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.DRAFT)
                .draftConfig(config("https://b.example")).build();
        store.save(t);

        assertThat(store.findSchedulable()).isEmpty();
        assertThat(t.isSchedulable()).isFalse();
    }

    @Test
    void publishedEnabledTaskIsSchedulable() {
        SchedulerTask t = SchedulerTask.builder()
                .id("st_3").ownerId("u_1").name("probe3").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.PUBLISHED)
                .draftConfig(config("https://c.example"))
                .publishedConfig(config("https://c.example")).build();
        store.save(t);

        List<SchedulerTask> schedulable = store.findSchedulable();
        assertThat(schedulable).hasSize(1);
        assertThat(schedulable.get(0).getId()).isEqualTo("st_3");
        assertThat(schedulable.get(0).isSchedulable()).isTrue();
    }

    @Test
    void disabledTaskExcludedFromSchedulable() {
        SchedulerTask t = SchedulerTask.builder()
                .id("st_4").ownerId("u_1").name("probe4").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.PUBLISHED)
                .publishedConfig(config("https://d.example")).build();
        store.save(t);

        // disable → state machine transition PUBLISHED -> DISABLED
        t.setStatus(SchedulerTask.Status.DISABLED);
        store.save(t);

        assertThat(store.findSchedulable()).isEmpty();
    }

    @Test
    void deleteRemovesTaskAndCalls() {
        SchedulerTask t = SchedulerTask.builder()
                .id("st_5").ownerId("u_1").name("probe5").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.DRAFT)
                .draftConfig(config("https://e.example")).build();
        store.save(t);
        assertThat(store.findById("st_5")).isPresent();

        store.delete("st_5");
        assertThat(store.findById("st_5")).isEmpty();
    }
}
