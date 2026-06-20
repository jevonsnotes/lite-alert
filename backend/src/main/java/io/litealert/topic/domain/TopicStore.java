package io.litealert.topic.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TopicStore {

    private final TopicMapper mapper;

    public Optional<Topic> findById(String id) {
        Topic t = mapper.selectOneById(id);
        return t == null ? Optional.empty() : Optional.of(t);
    }

    public Optional<Topic> findByNamespaceAndName(String namespaceId, String name) {
        QueryWrapper qw = QueryWrapper.create()
                .where("namespace_id = ?", namespaceId)
                .and("lower(name) = lower(?)", name);
        Topic t = mapper.selectOneByQuery(qw);
        return t == null ? Optional.empty() : Optional.of(t);
    }

    public Optional<Topic> findForWebhook(String namespaceName, String topicName) {
        QueryWrapper qw = QueryWrapper.create()
                .where("lower(namespace_name) = lower(?)", namespaceName)
                .and("lower(name) = lower(?)", topicName);
        Topic t = mapper.selectOneByQuery(qw);
        return t == null ? Optional.empty() : Optional.of(t);
    }

    public List<Topic> findByNamespace(String namespaceId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("namespace_id = ?", namespaceId)
                .orderBy("created_at desc, name asc");
        return mapper.selectListByQuery(qw);
    }

    public List<Topic> findByOwner(String ownerId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("owner_id = ?", ownerId)
                .orderBy("created_at desc, name asc");
        return mapper.selectListByQuery(qw);
    }

    public List<Topic> findAll() {
        QueryWrapper qw = QueryWrapper.create()
                .orderBy("created_at desc, name asc");
        return mapper.selectListByQuery(qw);
    }

    public synchronized Topic save(Topic t) {
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

    public synchronized void deleteByNamespace(String namespaceId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("namespace_id = ?", namespaceId);
        mapper.deleteByQuery(qw);
    }
}
