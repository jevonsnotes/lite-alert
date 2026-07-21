package io.litealert.scheduler;

import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.TaskConfig;
import io.litealert.scheduler.domain.SchedulerTask;
import io.litealert.scheduler.domain.SchedulerTaskType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTaskDiffServiceTest {

    private final SchedulerTaskDiffService diff = new SchedulerTaskDiffService();

    private ApiTaskConfig cfg(String url) {
        ApiTaskConfig c = new ApiTaskConfig();
        c.setMethod("GET");
        c.setUrl(url);
        return c;
    }

    private SchedulerTask task(ApiTaskConfig draft, ApiTaskConfig published) {
        SchedulerTask t = SchedulerTask.builder()
                .id("st_1").ownerId("u_1").name("probe").taskType(SchedulerTaskType.API)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.PUBLISHED)
                .draftConfig(draft).publishedConfig(published).build();
        return t;
    }

    private io.litealert.scheduler.domain.TcpTaskConfig tcp(String host, int port) {
        io.litealert.scheduler.domain.TcpTaskConfig c = new io.litealert.scheduler.domain.TcpTaskConfig();
        c.setHost(host);
        c.setPort(port);
        return c;
    }

    private SchedulerTask tcpTask(io.litealert.scheduler.domain.TcpTaskConfig draft,
                                  io.litealert.scheduler.domain.TcpTaskConfig published) {
        return SchedulerTask.builder()
                .id("st_tcp").ownerId("u_1").name("tcp-probe").taskType(SchedulerTaskType.TCP)
                .cron("0 */5 * * * *").enabled(true).status(SchedulerTask.Status.PUBLISHED)
                .draftConfig(draft).publishedConfig(published).build();
    }

    @Test
    void tcpHostChangeDetected() {
        io.litealert.scheduler.domain.TcpTaskConfig published = tcp("10.0.0.5", 3306);
        io.litealert.scheduler.domain.TcpTaskConfig draft = tcp("10.0.0.6", 3306);
        SchedulerTaskDiffService.DiffResult r = diff.diff(tcpTask(draft, published));
        assertThat(r.hasPendingChanges()).isTrue();
        assertThat(r.diffs()).anySatisfy(e -> {
            assertThat(e.field()).isEqualTo("host");
            assertThat(e.oldValue()).isEqualTo("10.0.0.5");
            assertThat(e.newValue()).isEqualTo("10.0.0.6");
        });
    }

    @Test
    void tcpNoChangeWhenDraftEqualsPublished() {
        io.litealert.scheduler.domain.TcpTaskConfig c = tcp("10.0.0.5", 3306);
        SchedulerTaskDiffService.DiffResult r = diff.diff(tcpTask(c, c));
        assertThat(r.hasPendingChanges()).isFalse();
        assertThat(r.diffs()).isEmpty();
    }

    @Test
    void noChangeWhenDraftEqualsPublished() {
        ApiTaskConfig c = cfg("https://a.example");
        SchedulerTaskDiffService.DiffResult r = diff.diff(task(c, c));
        assertThat(r.hasPendingChanges()).isFalse();
        assertThat(r.diffs()).isEmpty();
    }

    @Test
    void emptyCollectionEquivalentToNull() {
        ApiTaskConfig published = cfg("https://a.example");
        ApiTaskConfig draft = cfg("https://a.example");
        draft.setHeaders(List.of()); // empty list vs null → equivalent
        SchedulerTaskDiffService.DiffResult r = diff.diff(task(draft, published));
        assertThat(r.hasPendingChanges()).isFalse();
    }

    @Test
    void urlChangeDetected() {
        SchedulerTaskDiffService.DiffResult r = diff.diff(task(cfg("https://new.example"), cfg("https://old.example")));
        assertThat(r.hasPendingChanges()).isTrue();
        assertThat(r.diffs()).anySatisfy(e -> {
            assertThat(e.field()).isEqualTo("url");
            assertThat(e.changeType()).isEqualTo(SchedulerTaskDiffService.ChangeType.CHANGED);
            assertThat(e.oldValue()).isEqualTo("https://old.example");
            assertThat(e.newValue()).isEqualTo("https://new.example");
        });
    }

    @Test
    void headerAddRemoveChangeDetected() {
        ApiTaskConfig draft = cfg("https://a.example");
        draft.setHeaders(List.of(new ApiTaskConfig.Header("X-Keep", "v1"), new ApiTaskConfig.Header("X-New", "n")));
        ApiTaskConfig published = cfg("https://a.example");
        published.setHeaders(List.of(new ApiTaskConfig.Header("X-Keep", "v1"), new ApiTaskConfig.Header("X-Old", "o")));
        SchedulerTaskDiffService.DiffResult r = diff.diff(task(draft, published));
        assertThat(r.diffs()).anySatisfy(e -> { assertThat(e.field()).contains("X-New"); assertThat(e.changeType()).isEqualTo(SchedulerTaskDiffService.ChangeType.ADDED); });
        assertThat(r.diffs()).anySatisfy(e -> { assertThat(e.field()).contains("X-Old"); assertThat(e.changeType()).isEqualTo(SchedulerTaskDiffService.ChangeType.REMOVED); });
    }

    @Test
    void bodyTypeChangeDetected() {
        ApiTaskConfig draft = cfg("https://a.example");
        ApiTaskConfig.Body b = new ApiTaskConfig.Body();
        b.setType(ApiTaskConfig.Body.Type.RAW);
        b.setRawType(ApiTaskConfig.Body.RawType.JSON);
        b.setRawText("{\"a\":1}");
        draft.setBody(b);
        SchedulerTaskDiffService.DiffResult r = diff.diff(task(draft, cfg("https://a.example")));
        assertThat(r.hasPendingChanges()).isTrue();
        assertThat(r.diffs()).anySatisfy(e -> assertThat(e.field()).startsWith("body"));
    }

    @Test
    void assertionLogicAndConditionDetected() {
        ApiTaskConfig draft = cfg("https://a.example");
        ApiTaskConfig.Assertion a = new ApiTaskConfig.Assertion();
        a.setLogic(ApiTaskConfig.Assertion.Logic.OR);
        ApiTaskConfig.Condition c = new ApiTaskConfig.Condition();
        c.setPath("$.code"); c.setOperator(ApiTaskConfig.Condition.Operator.EQ); c.setExpected("1");
        a.setConditions(List.of(c));
        draft.setAssertion(a);

        ApiTaskConfig published = cfg("https://a.example");
        ApiTaskConfig.Assertion pa = new ApiTaskConfig.Assertion();
        pa.setLogic(ApiTaskConfig.Assertion.Logic.AND);
        ApiTaskConfig.Condition pc = new ApiTaskConfig.Condition();
        pc.setPath("$.code"); pc.setOperator(ApiKeyOp()); pc.setExpected("0");
        pa.setConditions(List.of(pc));
        published.setAssertion(pa);

        SchedulerTaskDiffService.DiffResult r = diff.diff(task(draft, published));
        assertThat(r.hasPendingChanges()).isTrue();
    }

    private static ApiTaskConfig.Condition.Operator ApiKeyOp() { return ApiTaskConfig.Condition.Operator.EQ; }

    @Test
    void neverPublishedWithContentIsAllPending() {
        ApiTaskConfig draft = cfg("https://a.example");
        SchedulerTask t = task(draft, null); // never published
        SchedulerTaskDiffService.DiffResult r = diff.diff(t);
        assertThat(r.hasPendingChanges()).isTrue();
    }

    @Test
    void neverPublishedEmptyDraftIsNoChange() {
        SchedulerTask t = task(new ApiTaskConfig(), null);
        SchedulerTaskDiffService.DiffResult r = diff.diff(t);
        assertThat(r.hasPendingChanges()).isFalse();
    }

    @Test
    void nameChangeDetectedViaMetaSnapshot() {
        ApiTaskConfig c = cfg("https://a.example");
        c.setMeta(new TaskConfig.Meta("old-name", null, "0 */5 * * * *"));
        SchedulerTask t = task(c, c);
        t.setName("new-name"); // draft row name differs from published snapshot
        SchedulerTaskDiffService.DiffResult r = diff.diff(t);
        assertThat(r.hasPendingChanges()).isTrue();
        assertThat(r.diffs()).anySatisfy(e -> {
            assertThat(e.field()).isEqualTo("name");
            assertThat(e.oldValue()).isEqualTo("old-name");
            assertThat(e.newValue()).isEqualTo("new-name");
        });
    }

    @Test
    void cronChangeDetectedViaMetaSnapshot() {
        ApiTaskConfig c = cfg("https://a.example");
        c.setMeta(new TaskConfig.Meta("p", null, "0 */5 * * * *"));
        SchedulerTask t = task(c, c);
        t.setCron("0 */1 * * * *");
        SchedulerTaskDiffService.DiffResult r = diff.diff(t);
        assertThat(r.hasPendingChanges()).isTrue();
        assertThat(r.diffs()).anySatisfy(e -> {
            assertThat(e.field()).isEqualTo("cron");
            assertThat(e.changeType()).isEqualTo(SchedulerTaskDiffService.ChangeType.CHANGED);
        });
    }

    @Test
    void nameAndCronUnchangedWhenMatchingSnapshot() {
        ApiTaskConfig c = cfg("https://a.example");
        c.setMeta(new TaskConfig.Meta("p", null, "0 */5 * * * *"));
        SchedulerTask t = task(c, c);
        t.setName("p");
        t.setCron("0 */5 * * * *");
        SchedulerTaskDiffService.DiffResult r = diff.diff(t);
        assertThat(r.hasPendingChanges()).isFalse();
    }

    @Test
    void notifyConfigIdsChangeDetected() {
        ApiTaskConfig c = cfg("https://a.example");
        c.setMeta(new TaskConfig.Meta("p", null, "0 */5 * * * *"));
        c.getMeta().setNotifyConfigIds(List.of("sn_a", "sn_b"));
        SchedulerTask t = task(c, c);
        t.setNotifyConfigIds(List.of("sn_b", "sn_c")); // drop sn_a, add sn_c
        SchedulerTaskDiffService.DiffResult r = diff.diff(t);
        assertThat(r.hasPendingChanges()).isTrue();
        assertThat(r.diffs()).anySatisfy(e -> { assertThat(e.field()).contains("sn_a"); assertThat(e.changeType()).isEqualTo(SchedulerTaskDiffService.ChangeType.REMOVED); });
        assertThat(r.diffs()).anySatisfy(e -> { assertThat(e.field()).contains("sn_c"); assertThat(e.changeType()).isEqualTo(SchedulerTaskDiffService.ChangeType.ADDED); });
    }
}
