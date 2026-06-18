package io.litealert.auth.domain;

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
public class UserStore {

    private final JdbcTemplate jdbc;

    public Optional<User> findById(String id) {
        List<User> rows = jdbc.query("select * from la_user where id = ?", this::map, id);
        return rows.stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        List<User> rows = jdbc.query("select * from la_user where lower(username) = lower(?)", this::map, username);
        return rows.stream().findFirst();
    }

    public List<User> findAll() {
        return jdbc.query("select * from la_user order by created_at desc, username asc", this::map);
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject("select count(*) from la_user where lower(username) = lower(?)",
                Integer.class, username);
        return count != null && count > 0;
    }

    public synchronized User save(User u) {
        boolean exists = findById(u.getId()).isPresent();
        if (exists) {
            jdbc.update("update la_user set username=?, password_hash=?, role=?, enabled=?, created_at=?, created_by=?, updated_at=?, last_login_at=? where id=?",
                    u.getUsername(), u.getPasswordHash(), u.getRole().name(), u.isEnabled(),
                    ts(u.getCreatedAt()), u.getCreatedBy(), ts(u.getUpdatedAt()), ts(u.getLastLoginAt()), u.getId());
        } else {
            jdbc.update("insert into la_user(id, username, password_hash, role, enabled, created_at, created_by, updated_at, last_login_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    u.getId(), u.getUsername(), u.getPasswordHash(), u.getRole().name(), u.isEnabled(),
                    ts(u.getCreatedAt()), u.getCreatedBy(), ts(u.getUpdatedAt()), ts(u.getLastLoginAt()));
        }
        return u;
    }

    public synchronized void delete(String id) {
        jdbc.update("delete from la_user where id = ?", id);
    }

    public boolean hasAnyAdmin() {
        Integer count = jdbc.queryForObject("select count(*) from la_user where role = 'ADMIN'", Integer.class);
        return count != null && count > 0;
    }

    private User map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return User.builder()
                .id(rs.getString("id"))
                .username(rs.getString("username"))
                .passwordHash(rs.getString("password_hash"))
                .role(User.Role.valueOf(rs.getString("role")))
                .enabled(rs.getBoolean("enabled"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .createdBy(rs.getString("created_by"))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .lastLoginAt(instant(rs.getTimestamp("last_login_at")))
                .build();
    }

    private Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
