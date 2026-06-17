package io.litealert.topic.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
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
 * <p>Legacy fields {@code transform} / {@code notifyTemplate} are still
 * deserialized so older topic files keep working; they're folded into the
 * EMAIL channel template at load time and are not written back.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Topic {

    public enum Status { DRAFT, PUBLISHED, DISABLED }

    public enum AuthMode { API_KEY, NONE }

    private String id;
    private String namespaceId;
    private String namespaceName;
    private String name;
    private String description;
    private String ownerId;

    private Status status;

    @Builder.Default
    private Auth auth = new Auth();

    /** JSON Schema (Draft 2020-12) for the inbound payload. */
    private JsonNode inboundFormat;

    /** Per-channel notify configuration. */
    @Builder.Default
    private Map<NotifyTarget.Type, ChannelTemplate> templates = new EnumMap<>(NotifyTarget.Type.class);

    // ---------- Legacy compatibility ----------
    /** @deprecated use {@link #getTemplates()} (WEBHOOK entry's {@code transform}). */
    @Deprecated
    private Transform transform;

    /** @deprecated use {@link #getTemplates()} (EMAIL entry). */
    @Deprecated
    private NotifyTemplate notifyTemplate;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;

    /**
     * Returns the channel template for a given target type, falling back to
     * any-channel defaults — including legacy {@code notifyTemplate} fields
     * promoted to EMAIL when no explicit map entry exists.
     */
    @JsonIgnore
    public ChannelTemplate templateFor(NotifyTarget.Type type) {
        ChannelTemplate t = templates == null ? null : templates.get(type);
        if (t != null) return t;
        if (type == NotifyTarget.Type.EMAIL && notifyTemplate != null) {
            ChannelTemplate fallback = new ChannelTemplate();
            fallback.setSubject(notifyTemplate.getSubject());
            fallback.setBody(notifyTemplate.getBody());
            return fallback;
        }
        return null;
    }

    @Data
    public static class Auth {
        private AuthMode mode = AuthMode.API_KEY;
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
        /** WEBHOOK only: outbound JSON body string (preferred over body). */
        private JsonNode outputTemplate;
        /** WEBHOOK only: transform mappings. */
        private Transform transform;
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

    /** @deprecated kept for legacy file compatibility only. */
    @Deprecated
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotifyTemplate {
        private String subject;
        private String body;
    }
}
