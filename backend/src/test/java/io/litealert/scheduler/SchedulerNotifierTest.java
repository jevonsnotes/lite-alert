package io.litealert.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.notify.template.TemplateRenderer;
import io.litealert.scheduler.domain.ApiTaskConfig;
import io.litealert.scheduler.domain.SchedulerNotifyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerNotifierTest {

    private SchedulerNotifier notifier;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        notifier = new SchedulerNotifier(new TemplateRenderer(mapper), new ApiTaskHttpExecutor(), mapper);
    }

    private SchedulerNotifyConfig cfg(String body) {
        return SchedulerNotifyConfig.builder()
                .id("sn_1").ownerId("u_1").name("n").method("POST").url("https://hook")
                .bodyTemplate(body).enabled(true).build();
    }

    private SchedulerNotifier.RenderContext ctx(boolean success, String responseBody, String error) {
        return new SchedulerNotifier.RenderContext("st_1", "order-probe", "API", success,
                200, 42L, error, Instant.now(), true, responseBody);
    }

    private SchedulerNotifier.RenderContext tcpCtx(boolean success, String error) {
        return new SchedulerNotifier.RenderContext("st_tcp", "tcp-probe", "TCP", success,
                null, 42L, error, Instant.now(), null, null);
    }

    @Test
    void rendersBuiltinVariables() {
        SchedulerNotifyConfig c = cfg("{\"text\":\"任务 {{taskName}} {{status}}：{{error}}\",\"ms\":{{durationMs}}}");
        String rendered = notifier.renderBody(c, ctx(false, "{}", "断言失败：$.code=1"));
        assertThat(rendered).contains("任务 order-probe FAIL：断言失败：$.code=1");
        assertThat(rendered).contains("\"ms\":42");
    }

    @Test
    void rendersResponseJsonPath() {
        SchedulerNotifyConfig c = cfg("{\"diff\":\"{{$.response.data.diff}}\"}");
        String rendered = notifier.renderBody(c, ctx(false, "{\"data\":{\"diff\":3}}", null));
        assertThat(rendered).contains("\"diff\":\"3\"");
    }

    @Test
    void undefinedVariableDegradesToEmpty() {
        SchedulerNotifyConfig c = cfg("{\"x\":\"{{$.response.no.such.field}}\",\"y\":\"{{undefinedVar}}\"}");
        String rendered = notifier.renderBody(c, ctx(true, "{\"data\":{}}", null));
        assertThat(rendered).contains("\"x\":\"\"").contains("\"y\":\"\"");
    }

    @Test
    void nonJsonResponseExposedAsText() {
        SchedulerNotifyConfig c = cfg("{\"raw\":\"{{$.response}}\"}");
        String rendered = notifier.renderBody(c, ctx(true, "plain text body", null));
        assertThat(rendered).contains("plain text body");
    }

    @Test
    void rendersProtocolVariableForTcpTask() {
        SchedulerNotifyConfig c = cfg("{\"protocol\":\"{{protocol}}\",\"status\":\"{{status}}\",\"http\":\"{{httpStatus}}\"}");
        String rendered = notifier.renderBody(c, tcpCtx(false, "连接被拒绝"));
        assertThat(rendered).contains("\"protocol\":\"TCP\"");
        assertThat(rendered).contains("\"status\":\"FAIL\"");
        // TCP task exposes no httpStatus -> empty string
        assertThat(rendered).contains("\"http\":\"\"");
    }

    @Test
    void shouldFireFailOnFailure() {
        SchedulerNotifyConfig c = cfg("{}");
        c.setTriggerOn(SchedulerNotifyConfig.TriggerOn.FAIL);
        assertThat(notifier.shouldFire(c, false)).isTrue();
        assertThat(notifier.shouldFire(c, true)).isFalse();
    }

    @Test
    void shouldFireSuccessOnSuccess() {
        SchedulerNotifyConfig c = cfg("{}");
        c.setTriggerOn(SchedulerNotifyConfig.TriggerOn.SUCCESS);
        assertThat(notifier.shouldFire(c, true)).isTrue();
        assertThat(notifier.shouldFire(c, false)).isFalse();
    }

    @Test
    void shouldFireAlwaysRegardless() {
        SchedulerNotifyConfig c = cfg("{}");
        c.setTriggerOn(SchedulerNotifyConfig.TriggerOn.ALWAYS);
        assertThat(notifier.shouldFire(c, true)).isTrue();
        assertThat(notifier.shouldFire(c, false)).isTrue();
    }

    @Test
    void shouldFireFalseWhenDisabled() {
        SchedulerNotifyConfig c = cfg("{}");
        c.setEnabled(false);
        c.setTriggerOn(SchedulerNotifyConfig.TriggerOn.ALWAYS);
        assertThat(notifier.shouldFire(c, true)).isFalse();
    }

    @Test
    void toSendConfigBuildsRawJsonBody() {
        SchedulerNotifyConfig c = cfg("{\"text\":\"{{taskName}}\"}");
        c.setMethod("PUT");
        ApiTaskConfig send = notifier.toSendConfig(c, ctx(true, "{}", null));
        assertThat(send.getMethod()).isEqualTo("PUT");
        assertThat(send.getBody().getType()).isEqualTo(ApiTaskConfig.Body.Type.RAW);
        assertThat(send.getBody().getRawType()).isEqualTo(ApiTaskConfig.Body.RawType.JSON);
        assertThat(send.getBody().getRawText()).contains("order-probe");
    }
}
