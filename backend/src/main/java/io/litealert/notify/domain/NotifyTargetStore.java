package io.litealert.notify.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class NotifyTargetStore {

    private final NotifyTargetMapper mapper;

    public List<NotifyTarget> findByUser(String userId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("user_id = ?", userId)
                .orderBy("created_at desc, label asc");
        return mapper.selectListByQuery(qw);
    }

    public List<NotifyTarget> findAll() {
        QueryWrapper qw = QueryWrapper.create()
                .orderBy("created_at desc, label asc");
        return mapper.selectListByQuery(qw);
    }

    public Optional<NotifyTarget> findById(String id) {
        NotifyTarget t = mapper.selectOneById(id);
        return t == null ? Optional.empty() : Optional.of(t);
    }

    public synchronized NotifyTarget save(NotifyTarget t) {
        if (findById(t.getId()).isPresent()) {
            mapper.update(t);
        } else {
            mapper.insert(t);
        }
        return t;
    }

    public synchronized void delete(String id) {
        mapper.deleteById(id);
    }
}

