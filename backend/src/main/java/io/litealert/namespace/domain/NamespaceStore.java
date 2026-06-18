package io.litealert.namespace.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class NamespaceStore {

    private final JdbcTemplate jdbc;

    public Optional<Namespace> findById(String id) {
        return jdbc.query("select * from la_namespace where id = ?", this::map, id).stream().findFirst();
    }

    public Optional<Namespace> findByName(String name) {
        if (name == null) return Optional.empty();
        return jdbc.query("select * from la_namespace where lower(name) = lower(?)", this::map, name).stream().findFirst();
    }

    public List<Namespace> findAll() {
        return jdbc.query("select * from la_namespace order by created_at desc, name asc", this::map);
    }

    public List<Namespace> findByOwner(String ownerId) {
        return jdbc.query("select * from la_namespace where owner_id = ? order by created_at desc, name asc", this::map, ownerId);
    }

    public synchronized Namespace save(Namespace n) {
        if (n.getStatus() == null) n.setStatus(Namespace.Status.ACTIVE);
        boolean exists = findById(n.getId()).isPresent();
        if (exists) {
            jdbc.update("update la_namespace set name=?, owner_id=?, description=?, status=?, disabled_at=?, disabled_by=?, created_at=?, updated_at=? where id=?",
                    n.getName(), n.getOwnerId(), n.getDescription(), n.getStatus().name(),
                    ts(n.getDisabledAt()), n.getDisabledBy(), ts(n.getCreatedAt()), ts(n.getUpdatedAt()), n.getId());
        } else {
            jdbc.update("insert into la_namespace(id, name, owner_id, description, status, disabled_at, disabled_by, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    n.getId(), n.getName(), n.getOwnerId(), n.getDescription(), n.getStatus().name(),
                    ts(n.getDisabledAt()), n.getDisabledBy(), ts(n.getCreatedAt()), ts(n.getUpdatedAt()));
        }
        return n;
    }

    public synchronized void delete(String id) {
        jdbc.update("delete from la_namespace where id = ?", id);
    }

    private Namespace map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return Namespace.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .ownerId(rs.getString("owner_id"))
                .description(rs.getString("description"))
                .status(Namespace.Status.valueOf(rs.getString("status")))
                .disabledAt(instant(rs.getTimestamp("disabled_at")))
                .disabledBy(rs.getString("disabled_by"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .build();
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
