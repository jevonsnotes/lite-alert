package io.litealert.topic.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TopicChannelTemplateStore {

    private final TopicChannelTemplateMapper mapper;

    public List<TopicChannelTemplate> findByTopicId(String topicId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("topic_id = ?", topicId)
                .orderBy("channel_type asc");
        return mapper.selectListByQuery(qw);
    }

    /** Batch-load templates for many topics at once to avoid N+1. */
    public Map<String, List<TopicChannelTemplate>> findByTopicIds(Collection<String> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) return Map.of();
        Object[] args = topicIds.toArray();
        QueryWrapper qw = QueryWrapper.create()
                .where("topic_id in (" + placeholders(args.length) + ")", args)
                .orderBy("topic_id asc, channel_type asc");
        List<TopicChannelTemplate> rows = mapper.selectListByQuery(qw);
        return rows.stream().collect(Collectors.groupingBy(TopicChannelTemplate::getTopicId, LinkedHashMap::new, Collectors.toList()));
    }

    /** Delete all existing templates for the given topic and insert the new set. */
    public void saveForTopic(String topicId, List<TopicChannelTemplate> templates) {
        deleteByTopicId(topicId);
        if (templates == null || templates.isEmpty()) return;
        Instant now = Instant.now();
        for (TopicChannelTemplate t : templates) {
            t.setTopicId(topicId);
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            mapper.insert(t);
        }
    }

    public void deleteByTopicId(String topicId) {
        QueryWrapper qw = QueryWrapper.create()
                .where("topic_id = ?", topicId);
        mapper.deleteByQuery(qw);
    }

    /** Copy templates from one topic to another (used by topic copy). */
    public void copy(String sourceTopicId, String targetTopicId) {
        List<TopicChannelTemplate> sources = findByTopicId(sourceTopicId);
        if (sources.isEmpty()) return;
        Instant now = Instant.now();
        for (TopicChannelTemplate src : sources) {
            TopicChannelTemplate copy = TopicChannelTemplate.builder()
                    .topicId(targetTopicId)
                    .channelType(src.getChannelType())
                    .subject(src.getSubject())
                    .body(src.getBody())
                    .outputFormat(src.getOutputFormat())
                    .outputTemplate(src.getOutputTemplate())
                    .outputXmlTemplate(src.getOutputXmlTemplate())
                    .transform(src.getTransform())
                    .responseCheck(src.getResponseCheck())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            mapper.insert(copy);
        }
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
