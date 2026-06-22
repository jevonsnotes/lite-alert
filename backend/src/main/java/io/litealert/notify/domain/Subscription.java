package io.litealert.notify.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO returned to callers of the subscription API.  The backing storage is
 * now the relational {@code la_topic_contact} table, managed by
 * {@link TopicContactStore}.  MyBatis-Flex annotations have been removed
 * because this class is no longer a direct entity.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Subscription {

    private String topicId;

    @Builder.Default
    private List<String> contactIds = new ArrayList<>();
}
