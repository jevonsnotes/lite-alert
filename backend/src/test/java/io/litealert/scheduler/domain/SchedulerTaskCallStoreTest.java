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
        jdbc.execute("drop table if exists la_scheduler_task");
        jdbc.execute("drop table if exists la_user");
        jdbc.execute("create table la_user (id varchar(64) primary key, username varchar(64))");
        jdbc.execute("create table la_scheduler_task (id varchar(64) primary key, owner_id varchar(64), name varchar(128), task_type varchar(16))");
        jdbc.execute("create table la_scheduler_task_call (" +
                "id varchar(64) primary key, task_id varchar(64), triggered_at timestamp, protocol varchar(8), " +
                "method varchar(16), url varchar(1024), tcp_target varchar(256), http_status int, tcp_ok boolean, " +
                "duration_ms bigint, success boolean, assertion_passed boolean, " +
                "error_message clob, response_excerpt clob, created_at timestamp)");
        store = new SchedulerTaskCallStore(jdbc);

        // seed two owners + three tasks: 2 API (one 200-pass, one 500-fail), 1 TCP (connected)
        jdbc.update("insert into la_user(id, username) values ('u_alice','alice')");
        jdbc.update("insert into la_user(id, username) values ('u_bob','bob')");
        jdbc.update("insert into la_scheduler_task(id, owner_id, name, task_type) values ('st_a1','u_alice','订单健康检查','API')");
        jdbc.update("insert into la_scheduler_task(id, owner_id, name, task_type) values ('st_a2','u_alice','库存探活','TCP')");
        jdbc.update("insert into la_scheduler_task(id, owner_id, name, task_type) values ('st_b1','u_bob','支付巡检','API')");
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

    // --- breakdown (sankey) ---

    private SchedulerTaskCall callWith(String id, String taskId, String protocol, Integer httpStatus,
                                       Boolean tcpOk, Boolean assertion, boolean success) {
        return SchedulerTaskCall.builder()
                .id(id).taskId(taskId).triggeredAt(Instant.now())
                .protocol(protocol).method(protocol == null || "API".equals(protocol) ? "GET" : null)
                .url(protocol == null || "API".equals(protocol) ? "https://x.example" : null)
                .httpStatus(httpStatus).tcpOk(tcpOk).durationMs(5L)
                .status(success ? SchedulerTaskCall.Status.SUCCESS : SchedulerTaskCall.Status.FAIL)
                .assertionPassed(assertion)
                .responseExcerpt("{}")
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void breakdownReturnsSixDimRowsAndTaskTotals() {
        // alice: 订单健康检查(API) x2 success/200/pass, x1 fail/500/no-assert
        store.insert(callWith("c1", "st_a1", "API", 200, null, true, true));
        store.insert(callWith("c2", "st_a1", "API", 200, null, true, true));
        store.insert(callWith("c3", "st_a1", "API", 500, null, null, false));
        // alice: 库存探活(TCP) connected success x1
        store.insert(callWith("c4", "st_a2", "TCP", null, true, null, true));

        Map<String, Object> out = store.breakdown(java.util.Set.of("st_a1", "st_a2"), null, null, 10);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totals = (List<Map<String, Object>>) out.get("taskTotals");

        assertThat(out.get("taskCount")).isEqualTo(2);
        // 3 distinct (task,type,status,assertion,result) combos: API200/pass/success, API500/no-assert/fail, TCP connected/no-assert/success
        assertThat(rows).hasSize(3);
        // each row has all 6 dim keys + count
        assertThat(rows.get(0).keySet()).containsExactly("owner", "taskName", "taskType", "status", "assertion", "result", "count");

        // find the API 200 success row -> count 2
        Map<String, Object> api200 = rows.stream()
                .filter(r -> "API".equals(r.get("taskType")) && "200".equals(r.get("status")) && "成功".equals(r.get("result")))
                .findFirst().orElseThrow();
        assertThat(api200.get("count")).isEqualTo(2L);
        assertThat(api200.get("owner")).isEqualTo("alice");
        assertThat(api200.get("assertion")).isEqualTo("通过");

        // TCP connected row -> status 已连接, assertion 无断言
        Map<String, Object> tcp = rows.stream()
                .filter(r -> "TCP".equals(r.get("taskType")))
                .findFirst().orElseThrow();
        assertThat(tcp.get("status")).isEqualTo("已连接");
        assertThat(tcp.get("assertion")).isEqualTo("无断言");
        assertThat(tcp.get("taskName")).isEqualTo("库存探活");

        // API 500 fail -> status "500"; assertion 无断言
        Map<String, Object> api500 = rows.stream()
                .filter(r -> "500".equals(r.get("status")))
                .findFirst().orElseThrow();
        assertThat(api500.get("result")).isEqualTo("失败");
        assertThat(api500.get("assertion")).isEqualTo("无断言");

        // taskTotals desc by count: 订单健康检查(3), 库存探活(1)
        assertThat(totals).hasSize(2);
        assertThat(totals.get(0).get("taskName")).isEqualTo("订单健康检查");
        assertThat(totals.get(0).get("count")).isEqualTo(3L);
        assertThat(totals.get(1).get("taskName")).isEqualTo("库存探活");
        assertThat(totals.get(1).get("count")).isEqualTo(1L);
    }

    @Test
    void breakdownFoldsTailTasksIntoOtherByLimit() {
        // 3 tasks each with 1 call, limit=2 -> third task folds into "其他"
        store.insert(callWith("c1", "st_a1", "API", 200, null, true, true));
        store.insert(callWith("c2", "st_a2", "TCP", null, true, null, true));
        store.insert(callWith("c3", "st_b1", "API", 404, null, null, false));

        Map<String, Object> out = store.breakdown(java.util.Set.of("st_a1", "st_a2", "st_b1"), null, null, 2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        // exactly one row carries the folded "其他" taskName
        assertThat(rows.stream().filter(r -> "其他".equals(r.get("taskName")))).hasSize(1);
        // taskTotals still returns all 3 (untruncated ranking)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totals = (List<Map<String, Object>>) out.get("taskTotals");
        assertThat(totals).hasSize(3);
        assertThat(out.get("taskCount")).isEqualTo(3);
    }

    @Test
    void breakdownEmptyTaskSetReturnsZero() {
        Map<String, Object> out = store.breakdown(java.util.Set.of(), null, null, 10);
        assertThat(out.get("rows")).isEqualTo(List.of());
        assertThat(out.get("taskTotals")).isEqualTo(List.of());
        assertThat(out.get("taskCount")).isEqualTo(0);
    }

    @Test
    void breakdownScopedToVisibleTaskIds() {
        // bob's task st_b1 has calls, but only st_a1 is visible -> only alice's task appears
        store.insert(callWith("c1", "st_a1", "API", 200, null, true, true));
        store.insert(callWith("c2", "st_b1", "API", 200, null, true, true));

        Map<String, Object> out = store.breakdown(java.util.Set.of("st_a1"), null, null, 10);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("owner")).isEqualTo("alice");
        assertThat(out.get("taskCount")).isEqualTo(1);
    }
}
