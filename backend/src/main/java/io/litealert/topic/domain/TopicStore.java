package io.litealert.topic.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TopicStore {

    private final TopicMapper mapper;
    private final TopicChannelTemplateStore templateStore;

    public Optional<Topic> findById(String id) {
        Topic t = mapper.selectOneById(id);
        if (t != null) {
            t.assembleTemplates(templateStore.findByTopicId(id));
        }
        return t == null ? Optional.empty() : Optional.of(t);
    }

    public Optional<Topic> findByNamespaceAndName(String namespaceId, String name) {
        QueryWrapper qw = QueryWrapper.create()
                .where("namespace_id = ?", namespaceId)
                .and("lower(name) = lower(?)", name);
        Topic t = mapper.selectOneByQuery(qw);
        if (t != null) {
            t.assembleTemplates(templateStore.findByTopicId(t.getId()));
        }
        return t == null ? Optional.empty() : Optional.of(t);
    }

    public Optional<Topic> findForWebhook(String namespaceName, String topicName) {
        QueryWrapper qw = QueryWrapper.create()
                .where("lower(namespace_name) = lower(?)", namespaceName)
                .and("lower(name) = lower(?)", topicName);
        Topic t = mapper.selectOneByQuery(qw);
        if (t != null) {
            t.assembleTemplates(templateStore.findByTopicId(t.getId()));
        }
        return t == null ? Optional.empty() : Optional.of(t);
    }

    public List<Topic> findByNamespace(String namespaceId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("namespace_id = ?", namespaceId)
                .orderBy("created_at desc, name asc");
        List<Topic> topics = mapper.selectListByQuery(qw);
        assembleTemplatesForTopics(topics);
        return topics;
    }

    public List<Topic> findByOwner(String ownerId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("owner_id = ?", ownerId)
                .orderBy("created_at desc, name asc");
        List<Topic> topics = mapper.selectListByQuery(qw);
        assembleTemplatesForTopics(topics);
        return topics;
    }

    public List<Topic> findAll() {
        QueryWrapper qw = QueryWrapper.create()
                .orderBy("created_at desc, name asc");
        List<Topic> topics = mapper.selectListByQuery(qw);
        assembleTemplatesForTopics(topics);
        return topics;
    }

    private void assembleTemplatesForTopics(List<Topic> topics) {
        if (topics.isEmpty()) return;
        Set<String> ids = topics.stream().map(Topic::getId).collect(Collectors.toSet());
        Map<String, List<TopicChannelTemplate>> byTopic = templateStore.findByTopicIds(ids);
        for (Topic t : topics) {
            t.assembleTemplates(byTopic.getOrDefault(t.getId(), List.of()));
        }
    }

    public synchronized Topic save(Topic t) {
        if (findByIdWithoutTemplates(t.getId()).isPresent()) {
            mapper.update(t);
        } else {
            mapper.insert(t);
        }
        templateStore.saveForTopic(t.getId(), t.disassembleTemplates());
        return t;
    }

    /** Internal find that does NOT assemble templates — used by save() to avoid infinite recursion. */
    private Optional<Topic> findByIdWithoutTemplates(String id) {
        Topic t = mapper.selectOneById(id);
        return t == null ? Optional.empty() : Optional.of(t);
    }

    public synchronized void delete(String id) {
        templateStore.deleteByTopicId(id);
        mapper.deleteById(id);
    }

    public synchronized void deleteByNamespace(String namespaceId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("namespace_id = ?", namespaceId);
        List<Topic> topics = mapper.selectListByQuery(qw);
        for (Topic t : topics) {
            templateStore.deleteByTopicId(t.getId());
        }
        mapper.deleteByQuery(qw);
    }
}
