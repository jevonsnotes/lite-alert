package io.litealert.topic.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TopicChannelTemplateStore}, focusing on the copy path.
 *
 * <p>Production hit a {@code duplicate key value violates unique constraint
 * uk_tct_topic_channel} on PostgreSQL when copying a topic whose source rows
 * contained repeated {@code channel_type} (legacy data). H2 in dev did not
 * surface it. The {@code copy} method must therefore de-duplicate by
 * {@code channelType} before inserting, so a duplicated source never produces
 * a conflicting target.
 */
class TopicChannelTemplateStoreTest {

    private TopicChannelTemplateMapper mapper;
    private TopicChannelTemplateStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(TopicChannelTemplateMapper.class);
        store = new TopicChannelTemplateStore(mapper);
    }

    @Test
    void copyDeDuplicatesByChannelType() {
        // Source topic carries TWO EMAIL rows (legacy duplicate) plus one FEISHU.
        // The unique constraint uk_tct_topic_channel(topic_id, channel_type)
        // would reject a naive 1:1 copy on PostgreSQL.
        TopicChannelTemplate emailA = template("EMAIL", "subject-a", "body-a");
        TopicChannelTemplate emailB = template("EMAIL", "subject-b", "body-b");
        TopicChannelTemplate feishu = template("FEISHU", "subject-f", "body-f");
        when(mapper.selectListByQuery(any())).thenReturn(List.of(emailA, emailB, feishu));

        store.copy("t_source", "t_target");

        // Each channel type must be inserted exactly once into the target,
        // regardless of how many duplicates exist in the source.
        verify(mapper, times(1)).insert(argThat(t -> t != null && "EMAIL".equals(t.getChannelType())));
        verify(mapper, times(1)).insert(argThat(t -> t != null && "FEISHU".equals(t.getChannelType())));
        verify(mapper, times(2)).insert(any(TopicChannelTemplate.class));
    }

    @Test
    void copyKeepsLastOccurrenceWhenChannelTypeDuplicates() {
        // When two rows share a channel type, the later one wins (stable order
        // from the source query: channel_type asc, then insertion order).
        TopicChannelTemplate first = template("EMAIL", "old-subject", "old-body");
        TopicChannelTemplate second = template("EMAIL", "new-subject", "new-body");
        when(mapper.selectListByQuery(any())).thenReturn(List.of(first, second));

        store.copy("t_source", "t_target");

        verify(mapper).insert(argThat(t -> "t_target".equals(t.getTopicId())
                && "EMAIL".equals(t.getChannelType())
                && "new-subject".equals(t.getSubject())
                && "new-body".equals(t.getBody())));
    }

    @Test
    void copyWritesNothingWhenSourceHasNoTemplates() {
        when(mapper.selectListByQuery(any())).thenReturn(List.of());

        store.copy("t_source", "t_target");

        verify(mapper, never()).insert(any(TopicChannelTemplate.class));
    }

    @Test
    void copyAlwaysTargetsTheNewTopicId() {
        when(mapper.selectListByQuery(any())).thenReturn(List.of(
                template("EMAIL", "s", "b"), template("DINGTALK", "s2", "b2")));

        store.copy("t_source", "t_target");

        verify(mapper).insert(argThat(t -> "t_target".equals(t.getTopicId())
                && "EMAIL".equals(t.getChannelType())));
        verify(mapper).insert(argThat(t -> "t_target".equals(t.getTopicId())
                && "DINGTALK".equals(t.getChannelType())));
    }

    private static TopicChannelTemplate template(String channelType, String subject, String body) {
        return TopicChannelTemplate.builder()
                .topicId("t_source")
                .channelType(channelType)
                .subject(subject)
                .body(body)
                .build();
    }
}
