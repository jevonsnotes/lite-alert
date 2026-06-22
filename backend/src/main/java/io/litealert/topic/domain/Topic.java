package io.litealert.topic.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import io.litealert.common.db.JacksonTypeHandler;
import io.litealert.notify.domain.NotifyTarget;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A Topic now carries one {@link ChannelTemplate} per {@link NotifyTarget.Type}
 * so each delivery channel can have its own subject/body/transform tuned to
 * what that channel needs:
 * <ul>
 *   <li>EMAIL → HTML body</li>
 *   <li>DINGTALK / FEISHU / WECOM → Markdown body</li>
 *   <li>WEBHOOK → arbitrary JSON body, optionally produced by the
 *       {@code outputTemplate} + {@code transform} mappings</li>
 * </ul>
 *
 * <p>Channel templates are persisted in the relational
 * {@code la_topic_channel_template} table.  The {@link #templates} field is
 * marked {@code @Column(ignore = true)} so MyBatis-Flex does not map it, but
 * Jackson still serializes it for API responses.  Use
 * {@link #assembleTemplates(List)} / {@link #disassembleTemplates()} to
 * convert between the in-memory Map and DB rows.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_topic")
public class Topic {

    public enum Status { DRAFT, PUBLISHED, DISABLED }

    public enum AuthMode { API_KEY, NONE }
    public enum KeyLocation { HEADER, QUERY }

    @Id(keyType = KeyType.None)
    private String id;

    @Column(value = "namespace_id")
    private String namespaceId;

    @Column(value = "namespace_name")
    private String namespaceName;

    @Column
    private String name;

    @Column
    private String description;

    @Column(value = "owner_id")
    private String ownerId;

    @Column
    private Status status;

    @Builder.Default
    @Column(value = "auth_json", typeHandler = JacksonTypeHandler.class)
    private Auth auth = new Auth();

    /** JSON Schema (Draft 2020-12) for the inbound payload. */
    @Column(value = "inbound_format_json", typeHandler = JacksonTypeHandler.class)
    private JsonNode inboundFormat;

    /**
     * Per-channel notify configuration.  Stored in {@code la_topic_channel_template}
     * (one row per channel) and assembled into this Map at load time.  Marked
     * {@code ignore = true} so MyBatis-Flex does not attempt column mapping;
     * Jackson still serializes it for API responses.
     */
    @Builder.Default
    @Column(ignore = true)
    private Map<NotifyTarget.Type, ChannelTemplate> templates = new EnumMap<>(NotifyTarget.Type.class);

    @Column(value = "created_at")
    private Instant createdAt;

    @Column(value = "updated_at")
    private Instant updatedAt;

    @Column(value = "published_at")
    private Instant publishedAt;

    /**
     * Returns the channel template for a given target type.
     */
    @JsonIgnore
    public ChannelTemplate templateFor(NotifyTarget.Type type) {
        return templates == null ? null : templates.get(type);
    }

    /**
     * Populate the in-memory {@link #templates} map from relational rows.
     * Called by {@link TopicStore} after loading from the database.
     */
    public void assembleTemplates(List<TopicChannelTemplate> rows) {
        Map<NotifyTarget.Type, ChannelTemplate> map = new EnumMap<>(NotifyTarget.Type.class);
        if (rows != null) {
            for (TopicChannelTemplate r : rows) {
                try {
                    NotifyTarget.Type type = NotifyTarget.Type.valueOf(r.getChannelType());
                    ChannelTemplate ch = new ChannelTemplate();
                    ch.setSubject(r.getSubject());
                    ch.setBody(r.getBody());
                    ch.setOutputFormat(r.getOutputFormat());
                    ch.setOutputTemplate(r.getOutputTemplate());
                    ch.setOutputXmlTemplate(r.getOutputXmlTemplate());
                    ch.setTransform(r.getTransform());
                    ch.setResponseCheck(r.getResponseCheck());
                    map.put(type, ch);
                } catch (IllegalArgumentException ignored) {
                    // skip unknown channel types from future versions
                }
            }
        }
        this.templates = map;
    }

    /**
     * Convert the in-memory {@link #templates} map into relational rows for
     * persistence.  Called by {@link TopicStore} before saving.
     */
    public List<TopicChannelTemplate> disassembleTemplates() {
        if (templates == null || templates.isEmpty()) return List.of();
        List<TopicChannelTemplate> rows = new ArrayList<>(templates.size());
        for (Map.Entry<NotifyTarget.Type, ChannelTemplate> e : templates.entrySet()) {
            ChannelTemplate ch = e.getValue();
            TopicChannelTemplate row = TopicChannelTemplate.builder()
                    .channelType(e.getKey().name())
                    .subject(ch.getSubject())
                    .body(ch.getBody())
                    .outputFormat(ch.getOutputFormat())
                    .outputTemplate(ch.getOutputTemplate())
                    .outputXmlTemplate(ch.getOutputXmlTemplate())
                    .transform(ch.getTransform())
                    .responseCheck(ch.getResponseCheck())
                    .build();
            rows.add(row);
        }
        return rows;
    }

    @Data
    public static class Auth {
        private AuthMode mode = AuthMode.API_KEY;
        private KeyLocation keyLocation = KeyLocation.HEADER;
        private List<String> ipWhitelist = new ArrayList<>();
        private RateLimit rateLimit;

        public Auth() {}

        public Auth(AuthMode mode, List<String> ipWhitelist, RateLimit rateLimit) {
            this.mode = mode == null ? AuthMode.API_KEY : mode;
            this.ipWhitelist = ipWhitelist == null ? new ArrayList<>() : ipWhitelist;
            this.rateLimit = rateLimit;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimit {
        private Integer perMinute;
        private Integer perIp;
    }

    /**
     * Per-channel rendering config.
     *
     * <p>Subject + body are always Mustache strings for email/chat channels.
     *
     * <p>{@code outputTemplate} is the WEBHOOK-specific outbound JSON body.
     * Users paste a full JSON object (may contain {@code {{var}} placeholders);
     * the engine resolves system/dynamic variables first, then overwrites fields
     * per the {@code transform.mappings} table.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelTemplate {
        /** Subject line (email title / chat title / webhook unused). */
        private String subject;
        /** Body — HTML for EMAIL, Markdown for chat.
         *  For WEBHOOK, the body is kept as the raw JSON template string
         *  (or {@code outputTemplate} if present). */
        private String body;
        /** WEBHOOK only: outbound format, JSON by default. */
        private String outputFormat;
        /** WEBHOOK only: outbound JSON body string (preferred over body). */
        private JsonNode outputTemplate;
        /** WEBHOOK only: outbound XML template string. */
        private String outputXmlTemplate;
        /** WEBHOOK only: transform mappings. */
        private Transform transform;
        /** WEBHOOK only: response body assertion. */
        private WebhookResponseCheck responseCheck;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebhookResponseCheck {
        private boolean enabled;
        private BodyType bodyType = BodyType.AUTO;
        private String successPath;
        private String successValue;
        private String messagePath;
        private Operator operator = Operator.EQ;

        public enum BodyType { AUTO, JSON, XML }
        public enum Operator { EQ, NE, CONTAINS, REGEX, GT, LT, EXISTS }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Transform {
        private boolean enabled;
        private List<Mapping> mappings = new ArrayList<>();

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Mapping {
            private String from;            // JSONPath
            private String to;              // dotted target path
            private String type;            // string|number|boolean|json|array<...>
            private boolean required;
            private Object defaultValue;
        }
    }
}
