package io.litealert.namespace.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class NamespaceStore {

    private final NamespaceMapper mapper;

    public Optional<Namespace> findById(String id) {
        Namespace n = mapper.selectOneById(id);
        return n == null ? Optional.empty() : Optional.of(n);
    }

    public Optional<Namespace> findByName(String name) {
        if (name == null) return Optional.empty();
        // Use raw SQL condition for case-insensitive match
        QueryWrapper qw = QueryWrapper.create()
                .where("lower(name) = lower(?)", name);
        return Optional.ofNullable(mapper.selectOneByQuery(qw));
    }

    public List<Namespace> findAll() {
        QueryWrapper qw = QueryWrapper.create()
                .orderBy("created_at desc, name asc");
        return mapper.selectListByQuery(qw);
    }

    public List<Namespace> findByOwner(String ownerId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("owner_id = ?", ownerId)
                .orderBy("created_at desc, name asc");
        return mapper.selectListByQuery(qw);
    }

    public synchronized Namespace save(Namespace n) {
        if (n.getStatus() == null) n.setStatus(Namespace.Status.ACTIVE);
        if (findById(n.getId()).isPresent()) {
            mapper.update(n);
        } else {
            mapper.insert(n);
        }
        return n;
    }

    public synchronized void delete(String id) {
        mapper.deleteById(id);
    }
}
