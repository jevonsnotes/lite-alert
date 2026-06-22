package io.litealert.notify.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TopicContactStore {

    private final TopicContactMapper mapper;

    public List<String> findContactIdsByTopicId(String topicId) {
        QueryWrapper qw = QueryWrapper.create()
                .select("contact_id")
                .where("topic_id = ?", topicId);
        List<TopicContact> rows = mapper.selectListByQuery(qw);
        List<String> ids = new ArrayList<>(rows.size());
        for (TopicContact c : rows) ids.add(c.getContactId());
        return ids;
    }

    /** Delete all existing contacts for the given topic and insert the new set. */
    public void saveForTopic(String topicId, List<String> contactIds) {
        deleteByTopicId(topicId);
        if (contactIds == null || contactIds.isEmpty()) return;
        Instant now = Instant.now();
        for (String cid : contactIds) {
            TopicContact c = TopicContact.builder()
                    .topicId(topicId)
                    .contactId(cid)
                    .createdAt(now)
                    .build();
            mapper.insert(c);
        }
    }

    public void deleteByTopicId(String topicId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("topic_id = ?", topicId);
        mapper.deleteByQuery(qw);
    }

    /** Copy contacts from one topic to another (used by topic copy). */
    public void copy(String sourceTopicId, String targetTopicId) {
        List<String> ids = findContactIdsByTopicId(sourceTopicId);
        if (ids.isEmpty()) return;
        saveForTopic(targetTopicId, ids);
    }
}
