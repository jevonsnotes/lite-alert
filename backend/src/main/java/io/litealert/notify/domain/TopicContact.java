package io.litealert.notify.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Relational row for a single subscription contact, previously stored as a
 * JSON array in {@code la_subscription.contact_ids_json}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_topic_contact")
public class TopicContact {

    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("topic_id")
    private String topicId;

    @Column("contact_id")
    private String contactId;

    @Column("created_at")
    private Instant createdAt;
}
