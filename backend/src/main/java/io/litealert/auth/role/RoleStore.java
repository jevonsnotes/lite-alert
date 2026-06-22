package io.litealert.auth.role;

import io.litealert.common.db.DbJson;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RoleStore {

    private final JdbcTemplate jdbc;

    public List<Role> findAll() {
        List<Role> roles = jdbc.query("select * from la_role order by system_builtin desc, name asc", this::mapRole);
        if (!roles.isEmpty()) {
            fillPermissions(roles);
        }
        return roles;
    }

    public Optional<Role> findById(String id) {
        List<Role> roles = jdbc.query("select * from la_role where id=?", this::mapRole, id);
        if (!roles.isEmpty()) {
            fillPermissions(roles);
        }
        return roles.stream().findFirst();
    }

    public List<Role> findByUser(String userId) {
        List<Role> roles = jdbc.query("select r.* from la_role r join la_user_role ur on r.id=ur.role_id where ur.user_id=? order by r.name asc", this::mapRole, userId);
        if (!roles.isEmpty()) {
            fillPermissions(roles);
        }
        return roles;
    }

    public synchronized Role save(Role r) {
        Instant now = Instant.now();
        boolean exists = findById(r.getId()).isPresent();
        if (exists) {
            jdbc.update("update la_role set name=?, description=?, system_builtin=?, updated_at=? where id=?",
                    r.getName(), r.getDescription(), r.isSystemBuiltin(), ts(now), r.getId());
        } else {
            jdbc.update("insert into la_role(id, name, description, system_builtin, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
                    r.getId(), r.getName(), r.getDescription(), r.isSystemBuiltin(), ts(r.getCreatedAt()), ts(now));
        }
        jdbc.update("delete from la_role_permission where role_id=?", r.getId());
        Set<String> perms = r.getPermissions();
        if (perms != null && !perms.isEmpty()) {
            for (String perm : perms) {
                jdbc.update("insert into la_role_permission(role_id, permission) values (?, ?)", r.getId(), perm);
            }
        }
        return r;
    }

    public synchronized void delete(String id) {
        jdbc.update("delete from la_role_permission where role_id=?", id);
        jdbc.update("delete from la_role where id=? and system_builtin=false", id);
    }

    public synchronized void setUserRoles(String userId, List<String> roleIds) {
        jdbc.update("delete from la_user_role where user_id=?", userId);
        for (String roleId : roleIds == null ? List.<String>of() : roleIds) {
            jdbc.update("insert into la_user_role(user_id, role_id) values (?, ?)", userId, roleId);
        }
    }

    public List<String> roleIdsByUser(String userId) {
        return jdbc.query("select role_id from la_user_role where user_id=?", (rs, n) -> rs.getString(1), userId);
    }

    private void fillPermissions(List<Role> roles) {
        List<String> ids = roles.stream().map(Role::getId).collect(Collectors.toList());
        String inClause = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String, Set<String>> perms = new LinkedHashMap<>();
        jdbc.query("select role_id, permission from la_role_permission where role_id in (" + inClause + ") order by role_id, permission",
                ids.toArray(), (rs, rowNum) -> {
                    perms.computeIfAbsent(rs.getString("role_id"), k -> new LinkedHashSet<>()).add(rs.getString("permission"));
                    return null;
                });
        for (Role role : roles) {
            role.setPermissions(perms.getOrDefault(role.getId(), new LinkedHashSet<>()));
        }
    }

    private Role mapRole(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return Role.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .systemBuiltin(rs.getBoolean("system_builtin"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .build();
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
