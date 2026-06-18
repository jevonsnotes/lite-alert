package io.litealert.namespace.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class Namespace {

    public enum Status { ACTIVE, DISABLED }

    private String id;
    private String name;             // globally unique
    private String ownerId;
    private String description;
    private Status status;
    private Instant disabledAt;
    private String disabledBy;
    private Instant createdAt;
    private Instant updatedAt;
}
