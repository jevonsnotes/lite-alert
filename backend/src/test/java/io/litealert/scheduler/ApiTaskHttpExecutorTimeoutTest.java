package io.litealert.scheduler;

import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.TaskConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTaskHttpExecutorTimeoutTest {

    private final ApiTaskHttpExecutor executor = new ApiTaskHttpExecutor();

    private ApiTaskConfig config(String url) {
        ApiTaskConfig c = new ApiTaskConfig();
        c.setMethod("GET");
        c.setUrl(url);
        return c;
    }

    @Test
    void defaultTimeoutsWhenUnset() {
        ApiTaskConfig c = config("https://x.example");
        // buildRequest uses default read/write (30s each) -> request timeout present
        HttpRequest req = executor.buildRequest(c);
        assertThat(req.timeout()).isPresent();
        assertThat(req.timeout().get().toSeconds()).isEqualTo(30);
    }

    @Test
    void customReadTimeoutDrivesRequestTimeout() {
        ApiTaskConfig c = config("https://x.example");
        c.setTimeouts(new TaskConfig.Timeouts(5, 10, 20));
        // buildRequest(config) still uses defaults; the per-task values flow through execute() path.
        // Verify the public buildRequest(config,read,write) reflects max(read,write).
        java.lang.reflect.Method m = null;
        try {
            m = ApiTaskHttpExecutor.class.getDeclaredMethod("buildRequest", ApiTaskConfig.class, int.class, int.class);
            m.setAccessible(true);
            HttpRequest req = (HttpRequest) m.invoke(executor, c, 10, 20);
            assertThat(req.timeout().get().toSeconds()).isEqualTo(20); // max(10,20)
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void zeroTimeoutMeansNoLimit() {
        ApiTaskConfig c = config("https://x.example");
        try {
            java.lang.reflect.Method m = ApiTaskHttpExecutor.class.getDeclaredMethod("buildRequest", ApiTaskConfig.class, int.class, int.class);
            m.setAccessible(true);
            HttpRequest req = (HttpRequest) m.invoke(executor, c, 0, 0);
            assertThat(req.timeout()).isEmpty(); // 0 = no limit -> .timeout not set
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void timeoutsDefaults() {
        assertThat(TaskConfig.Timeouts.DEFAULT_CONNECT).isEqualTo(5);
        assertThat(TaskConfig.Timeouts.DEFAULT_READ).isEqualTo(30);
        assertThat(TaskConfig.Timeouts.DEFAULT_WRITE).isEqualTo(30);
        TaskConfig.Timeouts t = new TaskConfig.Timeouts();
        assertThat(t.effectiveConnect()).isEqualTo(5);
        assertThat(t.effectiveRead()).isEqualTo(30);
        assertThat(t.effectiveWrite()).isEqualTo(30);
        TaskConfig.Timeouts custom = new TaskConfig.Timeouts(0, 60, 90);
        assertThat(custom.effectiveConnect()).isZero();
        assertThat(custom.effectiveRead()).isEqualTo(60);
        assertThat(custom.effectiveWrite()).isEqualTo(90);
    }
}
