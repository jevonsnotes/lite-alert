package io.litealert.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import io.litealert.notify.domain.NotifyTarget;
import io.litealert.topic.domain.Topic;

import java.util.Map;

/**
 * Strategy for delivering a single rendered message to a single target.
 *
 * <p>Implementations receive everything they could possibly need:
 * <ul>
 *   <li>{@code target} — endpoint + optional secret</li>
 *   <li>{@code template} — channel-specific subject/body/outputTemplate/transform</li>
 *   <li>{@code renderedSubject} / {@code renderedBody} — Mustache already
 *       applied; plain-text channels can use these directly</li>
 *   <li>{@code payload} — the inbound JSON, for channels (WEBHOOK) that
 *       compose their own body via an output-template + field mappings</li>
 *   <li>{@code systemVars} — namespace, topic, traceId, receivedAt, rawJson
 *       (available to webhook template engine for {{var}} resolution)</li>
 * </ul>
 */
public interface NotifyChannel {

    NotifyTarget.Type type();

    void send(NotifyTarget target,
              Topic.ChannelTemplate template,
              String renderedSubject,
              String renderedBody,
              JsonNode payload,
              Map<String, String> systemVars) throws Exception;
}
