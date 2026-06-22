package io.litealert.topic.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import io.litealert.common.db.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Relational row for a single channel template, previously stored as a JSON
 * blob in {@code la_topic.templates_json}.  WEBHOOK-only fields (output*,
 * transform, responseCheck) are nullable for non-webhook channels.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_topic_channel_template")
public class TopicChannelTemplate {

    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("topic_id")
    private String topicId;

    /** Normalized enum name: EMAIL, DINGTALK, FEISHU, WECOM, WEBHOOK. */
    @Column("channel_type")
    private String channelType;

    private String subject;
    private String body;

    @Column("output_format")
    private String outputFormat;

    @Column(value = "output_template", typeHandler = JacksonTypeHandler.class)
    private JsonNode outputTemplate;

    @Column("output_xml_template")
    private String outputXmlTemplate;

    @Column(value = "transform_json", typeHandler = JacksonTypeHandler.class)
    private Topic.Transform transform;

    @Column(value = "response_check_json", typeHandler = JacksonTypeHandler.class)
    private Topic.WebhookResponseCheck responseCheck;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
