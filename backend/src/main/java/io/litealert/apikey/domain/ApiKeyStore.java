package io.litealert.apikey.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import io.litealert.common.db.DbJson;
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
public class ApiKeyStore {

    private final JdbcTemplate jdbc;
    private final DbJson json;

    public Optional<ApiKey> findById(String id) {
        return jdbc.query("select * from la_api_key where id = ?", this::map, id).stream().findFirst();
    }

    public Optional<ApiKey> findByPrefix(String prefix) {
        return jdbc.query("select * from la_api_key where prefix = ?", this::map, prefix).stream().findFirst();
    }

    public List<ApiKey> findByOwner(String ownerId) {
        return jdbc.query("select * from la_api_key where owner_id = ? order by created_at desc, name asc", this::map, ownerId);
    }

    public synchronized ApiKey save(ApiKey k) {
        boolean exists = findById(k.getId()).isPresent();
        if (exists) {
            jdbc.update("update la_api_key set owner_id=?, name=?, prefix=?, key_hash=?, valid_from=?, valid_until=?, scopes_json=?, status=?, created_at=?, last_used_at=?, usage_count=?, rotate_count=?, rate_limit_per_minute=? where id=?",
                    k.getOwnerId(), k.getName(), k.getPrefix(), k.getKeyHash(), ts(k.getValidFrom()), ts(k.getValidUntil()),
                    json.write(k.getScopes()), k.getStatus().name(), ts(k.getCreatedAt()), ts(k.getLastUsedAt()),
                    k.getUsageCount(), k.getRotateCount(), k.getRateLimitPerMinute(), k.getId());
        } else {
            jdbc.update("insert into la_api_key(id, owner_id, name, prefix, key_hash, valid_from, valid_until, scopes_json, status, created_at, last_used_at, usage_count, rotate_count, rate_limit_per_minute) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    k.getId(), k.getOwnerId(), k.getName(), k.getPrefix(), k.getKeyHash(), ts(k.getValidFrom()), ts(k.getValidUntil()),
                    json.write(k.getScopes()), k.getStatus().name(), ts(k.getCreatedAt()), ts(k.getLastUsedAt()),
                    k.getUsageCount(), k.getRotateCount(), k.getRateLimitPerMinute());
        }
        return k;
    }

    public synchronized void delete(String id) {
        jdbc.update("delete from la_api_key where id = ?", id);
    }

    private ApiKey map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return ApiKey.builder()
                .id(rs.getString("id"))
                .ownerId(rs.getString("owner_id"))
                .name(rs.getString("name"))
                .prefix(rs.getString("prefix"))
                .keyHash(rs.getString("key_hash"))
                .validFrom(instant(rs.getTimestamp("valid_from")))
                .validUntil(instant(rs.getTimestamp("valid_until")))
                .scopes(json.read(rs.getString("scopes_json"), new TypeReference<java.util.List<ApiKey.Scope>>() {}, new java.util.ArrayList<>()))
                .status(ApiKey.Status.valueOf(rs.getString("status")))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .lastUsedAt(instant(rs.getTimestamp("last_used_at")))
                .usageCount(rs.getLong("usage_count"))
                .rotateCount(rs.getLong("rotate_count"))
                .rateLimitPerMinute(rs.getObject("rate_limit_per_minute") != null ? rs.getInt("rate_limit_per_minute") : null)
                .build();
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
}
