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
import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_subscription")
public class Subscription {

    @Id(keyType = KeyType.None)
    @Column("topic_id")
    private String topicId;

    @Column(value = "contact_ids_json", typeHandler = io.litealert.common.db.JsonListTypeHandler.class)
    @Builder.Default
    private List<String> contactIds = new ArrayList<>();

    @Column(value = "updated_at")
    private Instant updatedAt;
}
