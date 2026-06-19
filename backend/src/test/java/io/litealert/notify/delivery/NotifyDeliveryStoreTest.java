package io.litealert.notify.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.litealert.common.db.DbJson;
import io.litealert.notify.domain.NotifyTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyDeliveryStoreTest {

    private NotifyDeliveryStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:delivery_store_test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop table if exists la_notify_delivery");
        jdbc.execute("create table la_notify_delivery (" +
                "id varchar(64) primary key, trace_id varchar(128), topic_id varchar(64), target_id varchar(64), channel varchar(32), " +
                "payload_json clob, status varchar(32), attempt int, max_attempts int, next_retry_at timestamp, " +
                "locked_by varchar(128), locked_at timestamp, last_error clob, created_at timestamp, updated_at timestamp, finished_at timestamp)");
        store = new NotifyDeliveryStore(jdbc, new DbJson(new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void saveAndClaimDueTask() {
        NotifyDelivery d = NotifyDelivery.pending("tr_1", "t_1", "c_1", NotifyTarget.Type.WEBHOOK,
                new ObjectMapper().createObjectNode().put("password", "secret"));
        store.save(d);

        List<NotifyDelivery> due = store.findDue(Instant.now().plusSeconds(1), 10);
        boolean claimed = store.claim(due.get(0).getId(), "node-1", Instant.now(), Instant.now().plusSeconds(1));

        assertThat(due).hasSize(1);
        assertThat(claimed).isTrue();
        NotifyDelivery claimedTask = store.findById(d.getId()).orElseThrow();
        assertThat(claimedTask.getStatus()).isEqualTo(NotifyDelivery.Status.SENDING);
        assertThat(claimedTask.getLockedBy()).isEqualTo("node-1");
        assertThat(claimedTask.getPayloadJson()).contains("password");
    }
}
