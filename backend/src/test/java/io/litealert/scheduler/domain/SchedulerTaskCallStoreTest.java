package io.litealert.scheduler.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTaskCallStoreTest {

    private SchedulerTaskCallStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:scheduler_call_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop table if exists la_scheduler_task_call");
        jdbc.execute("create table la_scheduler_task_call (" +
                "id varchar(64) primary key, task_id varchar(64), triggered_at timestamp, protocol varchar(8), " +
                "method varchar(16), url varchar(1024), tcp_target varchar(256), http_status int, tcp_ok boolean, " +
                "duration_ms bigint, success boolean, assertion_passed boolean, " +
                "error_message clob, response_excerpt clob, created_at timestamp)");
        store = new SchedulerTaskCallStore(jdbc);
    }

    private SchedulerTaskCall call(String id, boolean success, String excerpt) {
        return call(id, success, excerpt, "st_1");
    }

    private SchedulerTaskCall call(String id, boolean success, String excerpt, String taskId) {
        return SchedulerTaskCall.builder()
                .id(id).taskId(taskId).triggeredAt(Instant.now()).method("GET").url("https://x.example")
                .httpStatus(200).durationMs(5L)
                .status(success ? SchedulerTaskCall.Status.SUCCESS : SchedulerTaskCall.Status.FAIL)
                .assertionPassed(success)
                .responseExcerpt(excerpt)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void totalsWithNullBoundsCountsAll() {
        store.insert(call("stc_1", true, "{\"code\":0}"));
        store.insert(call("stc_2", true, "{\"code\":0}"));
        store.insert(call("stc_3", false, "{\"code\":1}"));

        Map<String, Long> totals = store.totals(null, null);
        assertThat(totals.get("total")).isEqualTo(3L);
        assertThat(totals.get("success")).isEqualTo(2L);
    }

    @Test
    void totalsWithWindowFiltersByTime() {
        Instant past = Instant.now().minusSeconds(60);
        store.insert(call("stc_4", true, "{}"));
        Map<String, Long> totals = store.totals(Instant.now().plusSeconds(10), Instant.now().plusSeconds(20));
        assertThat(totals.get("total")).isEqualTo(0L);
    }

    @Test
    void dailyTrendWithNullBoundsBucketsAll() {
        store.insert(call("stc_5", true, "{}"));
        store.insert(call("stc_6", false, "{}"));
        assertThat(store.dailyTrend(null, null)).isNotEmpty();
    }

    @Test
    void sensitiveFieldsMaskedOnInsert() {
        store.insert(call("stc_7", true, "{\"token\":\"supersecret\",\"password\":\"hunter2\",\"code\":0}"));
        SchedulerTaskCall saved = store.findById("stc_7").orElseThrow();
        assertThat(saved.getResponseExcerpt()).contains("\"token\":\"***\"").contains("\"password\":\"***\"");
        assertThat(saved.getResponseExcerpt()).doesNotContain("supersecret").doesNotContain("hunter2");
    }

    @Test
    void findByTasksReturnsOnlyVisibleTaskIds() {
        store.insert(call("stc_a", true, "{}", "st_t1"));
        store.insert(call("stc_b", false, "{}", "st_t1"));
        store.insert(call("stc_c", true, "{}", "st_t2"));

        java.util.Set<String> visible = java.util.Set.of("st_t1");
        List<SchedulerTaskCall> result = store.findByTasks(visible, null, null, 50);
        assertThat(result).hasSize(2).allMatch(c -> "st_t1".equals(c.getTaskId()));

        assertThat(store.countByTasks(visible, null, null)).isEqualTo(2L);
        assertThat(store.countSuccessByTasks(visible, null, null)).isEqualTo(1L);
    }

    @Test
    void findByTasksEmptySetReturnsNothing() {
        store.insert(call("stc_d", true, "{}", "st_t1"));
        assertThat(store.findByTasks(java.util.Set.of(), null, null, 50)).isEmpty();
        assertThat(store.countByTasks(java.util.Set.of(), null, null)).isZero();
    }

    @Test
    void findPageReturnsRequestedPage() {
        // insert 5 records for st_t1, ordered by insert (triggeredAt = now per call)
        for (int i = 0; i < 5; i++) store.insert(call("stc_p" + i, i % 2 == 0, "{}", "st_t1"));
        java.util.Set<String> ids = java.util.Set.of("st_t1");
        // page 1 size 2 -> 2 items, total 5
        List<SchedulerTaskCall> p1 = store.findPage(ids, null, null, null, 1, 2);
        assertThat(p1).hasSize(2);
        assertThat(store.countByTasks(ids, null, null)).isEqualTo(5L);
        // page 3 size 2 -> 1 item (5 total, 2 per page -> page3 has 1)
        List<SchedulerTaskCall> p3 = store.findPage(ids, null, null, null, 3, 2);
        assertThat(p3).hasSize(1);
    }

    @Test
    void findPageBeyondRangeReturnsEmpty() {
        store.insert(call("stc_x", true, "{}", "st_t1"));
        List<SchedulerTaskCall> out = store.findPage(java.util.Set.of("st_t1"), null, null, null, 10, 20);
        assertThat(out).isEmpty();
    }

    @Test
    void findPageFiltersBySuccess() {
        store.insert(call("stc_ok", true, "{}", "st_t1"));
        store.insert(call("stc_no", false, "{}", "st_t1"));
        List<SchedulerTaskCall> onlyFail = store.findPage(java.util.Set.of("st_t1"), null, null, false, 1, 50);
        assertThat(onlyFail).hasSize(1).allMatch(c -> c.getStatus() == SchedulerTaskCall.Status.FAIL);
    }
}
