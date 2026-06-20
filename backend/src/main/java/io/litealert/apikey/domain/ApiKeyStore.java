package io.litealert.apikey.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ApiKeyStore {

    private final ApiKeyMapper mapper;

    public Optional<ApiKey> findById(String id) {
        ApiKey k = mapper.selectOneById(id);
        return k == null ? Optional.empty() : Optional.of(k);
    }

    public Optional<ApiKey> findByPrefix(String prefix) {
        QueryWrapper qw = QueryWrapper.create()
                .where("prefix = ?", prefix);
        return Optional.ofNullable(mapper.selectOneByQuery(qw));
    }

    public List<ApiKey> findByOwner(String ownerId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("owner_id = ?", ownerId)
                .orderBy("created_at desc, name asc");
        return mapper.selectListByQuery(qw);
    }

    public List<ApiKey> findAll() {
        QueryWrapper qw = QueryWrapper.create()
                .orderBy("created_at desc, name asc");
        return mapper.selectListByQuery(qw);
    }

    public synchronized ApiKey save(ApiKey k) {
        if (findById(k.getId()).isPresent()) {
            mapper.update(k);
        } else {
            mapper.insert(k);
        }
        return k;
    }

    public synchronized void delete(String id) {
        mapper.deleteById(id);
    }
}
