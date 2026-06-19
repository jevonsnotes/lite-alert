package io.litealert.auth.role;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.db.DbJson;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RoleStore {

    private final JdbcTemplate jdbc;
    private final DbJson json;

    public List<Role> findAll() {
        return jdbc.query("select * from la_role order by system_builtin desc, name asc", this::map);
    }

    public Optional<Role> findById(String id) {
        return jdbc.query("select * from la_role where id=?", this::map, id).stream().findFirst();
    }

    public List<Role> findByUser(String userId) {
        return jdbc.query("select r.* from la_role r join la_user_role ur on r.id=ur.role_id where ur.user_id=? order by r.name asc", this::map, userId);
    }

    public synchronized Role save(Role r) {
        boolean exists = findById(r.getId()).isPresent();
        if (exists) {
            jdbc.update("update la_role set name=?, description=?, system_builtin=?, permissions_json=?, updated_at=? where id=?",
                    r.getName(), r.getDescription(), r.isSystemBuiltin(), json.write(r.getPermissions()), ts(Instant.now()), r.getId());
        } else {
            jdbc.update("insert into la_role(id, name, description, system_builtin, permissions_json, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
                    r.getId(), r.getName(), r.getDescription(), r.isSystemBuiltin(), json.write(r.getPermissions()), ts(r.getCreatedAt()), ts(r.getUpdatedAt()));
        }
        return r;
    }

    public synchronized void delete(String id) { jdbc.update("delete from la_role where id=? and system_builtin=false", id); }

    public synchronized void setUserRoles(String userId, List<String> roleIds) {
        jdbc.update("delete from la_user_role where user_id=?", userId);
        for (String roleId : roleIds == null ? List.<String>of() : roleIds) {
            jdbc.update("insert into la_user_role(user_id, role_id) values (?, ?)", userId, roleId);
        }
    }

    public List<String> roleIdsByUser(String userId) {
        return jdbc.query("select role_id from la_user_role where user_id=?", (rs, n) -> rs.getString(1), userId);
    }

    private Role map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return Role.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .systemBuiltin(rs.getBoolean("system_builtin"))
                .permissions(json.read(rs.getString("permissions_json"), new TypeReference<Set<String>>() {}, new LinkedHashSet<>()))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .build();
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
