package io.litealert.scheduler.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.db.DbJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerNotifyConfigStoreTest {

    private SchedulerNotifyConfigStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:notify_cfg_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop table if exists la_scheduler_notify_config");
        jdbc.execute("create table la_scheduler_notify_config (" +
                "id varchar(64) primary key, owner_id varchar(64), name varchar(128), method varchar(16), " +
                "url varchar(1024), headers_json clob, body_template clob, trigger_on varchar(16), " +
                "enabled boolean, created_at timestamp, updated_at timestamp)");
        store = new SchedulerNotifyConfigStore(jdbc, new DbJson(new ObjectMapper().findAndRegisterModules()));
    }

    private SchedulerNotifyConfig cfg(String id, String owner, String name) {
        return SchedulerNotifyConfig.builder()
                .id(id).ownerId(owner).name(name).method("POST").url("https://hook.example")
                .bodyTemplate("{\"text\":\"{{taskName}}\"}").triggerOn(SchedulerNotifyConfig.TriggerOn.FAIL)
                .enabled(true).build();
    }

    @Test
    void saveAndFindByOwner() {
        store.save(cfg("sn_1", "u_a", "dingtalk"));
        store.save(cfg("sn_2", "u_b", "feishu"));

        assertThat(store.findByOwner("u_a")).hasSize(1);
        assertThat(store.findByOwner("u_a").get(0).getName()).isEqualTo("dingtalk");
        assertThat(store.findByOwner("u_b")).hasSize(1);
    }

    @Test
    void findByIdsReturnsMatching() {
        store.save(cfg("sn_1", "u_a", "a"));
        store.save(cfg("sn_2", "u_a", "b"));
        store.save(cfg("sn_3", "u_a", "c"));

        List<SchedulerNotifyConfig> result = store.findByIds(List.of("sn_1", "sn_3"));
        assertThat(result).hasSize(2);
    }

    @Test
    void findByIdsEmptyReturnsNothing() {
        store.save(cfg("sn_1", "u_a", "a"));
        assertThat(store.findByIds(List.of())).isEmpty();
        assertThat(store.findByIds(null)).isEmpty();
    }

    @Test
    void deleteRemovesConfig() {
        store.save(cfg("sn_1", "u_a", "a"));
        store.delete("sn_1");
        assertThat(store.findById("sn_1")).isEmpty();
    }
}
