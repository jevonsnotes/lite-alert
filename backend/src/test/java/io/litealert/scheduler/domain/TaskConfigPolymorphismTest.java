package io.litealert.scheduler.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.db.DbJson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polymorphic (de)serialization of {@link TaskConfig} (design D1). The {@code type} discriminator
 * must route JSON to {@link ApiTaskConfig} / {@link TcpTaskConfig}; {@link Meta}/{@link TaskConfig.Timeouts}
 * are inherited from the base.
 */
class TaskConfigPolymorphismTest {

    private final DbJson json = new DbJson(new ObjectMapper().findAndRegisterModules());

    @Test
    void apiConfigRoundTripsWithTypeDiscriminator() {
        ApiTaskConfig c = new ApiTaskConfig();
        c.setMethod("POST");
        c.setUrl("https://example.com/hook");

        String s = json.write(c);

        assertThat(s).contains("\"type\":\"API\"");
        TaskConfig back = json.read(s, TaskConfig.class);
        assertThat(back).isInstanceOf(ApiTaskConfig.class);
        assertThat(((ApiTaskConfig) back).getMethod()).isEqualTo("POST");
        assertThat(((ApiTaskConfig) back).getUrl()).isEqualTo("https://example.com/hook");
    }

    @Test
    void tcpConfigRoundTripsWithTypeDiscriminator() {
        TcpTaskConfig c = new TcpTaskConfig();
        c.setHost("10.0.0.5");
        c.setPort(3306);

        String s = json.write(c);

        assertThat(s).contains("\"type\":\"TCP\"");
        TaskConfig back = json.read(s, TaskConfig.class);
        assertThat(back).isInstanceOf(TcpTaskConfig.class);
        assertThat(((TcpTaskConfig) back).getHost()).isEqualTo("10.0.0.5");
        assertThat(((TcpTaskConfig) back).getPort()).isEqualTo(3306);
    }

    @Test
    void metaAndTimeoutsInheritedFromBase() {
        ApiTaskConfig c = new ApiTaskConfig();
        TaskConfig.Meta meta = new TaskConfig.Meta("probe", "desc", "0 */5 * * * *");
        meta.setNotifyConfigIds(java.util.List.of("snc_1"));
        c.setMeta(meta);
        c.setTimeouts(new TaskConfig.Timeouts(3, 20, 20));

        TaskConfig back = json.read(json.write(c), TaskConfig.class);
        assertThat(back).isInstanceOf(ApiTaskConfig.class);
        assertThat(back.getMeta().getName()).isEqualTo("probe");
        assertThat(back.getMeta().getCron()).isEqualTo("0 */5 * * * *");
        assertThat(back.getMeta().getNotifyConfigIds()).containsExactly("snc_1");
        assertThat(back.getTimeouts().getConnect()).isEqualTo(3);
    }

    @Test
    void unknownTypeReturnsNullViaStoreRead() {
        // SchedulerTaskStore tolerates a JSON with an unrecognized type by returning null rather than
        // throwing during startup recovery (design D1 risk mitigation). The store wraps read() in
        // try-catch; here we assert that an unknown discriminator cannot be mapped.
        String unknown = "{\"type\":\"PINGSOMETHING\",\"host\":\"x\"}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> json.read(unknown, TaskConfig.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingTypeFailsClosed() {
        // Old (pre-polymorphic) JSON without a discriminator cannot be safely mapped; the store treats
        // this as null (schedule recovery skips it) rather than guessing API.
        String legacy = "{\"method\":\"GET\",\"url\":\"https://x\"}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> json.read(legacy, TaskConfig.class))
                .isInstanceOf(IllegalStateException.class);
    }
}
