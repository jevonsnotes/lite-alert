package io.litealert.namespace.domain;

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

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table("la_namespace")
public class Namespace {

    public enum Status { ACTIVE, DISABLED }

    @Id(keyType = KeyType.None)
    private String id;

    @Column
    private String name;             // globally unique

    @Column(value = "owner_id")
    private String ownerId;

    @Column
    private String description;

    @Column
    private Status status;

    @Column(value = "disabled_at")
    private Instant disabledAt;

    @Column(value = "disabled_by")
    private String disabledBy;

    @Column(value = "created_at")
    private Instant createdAt;

    @Column(value = "updated_at")
    private Instant updatedAt;
}
