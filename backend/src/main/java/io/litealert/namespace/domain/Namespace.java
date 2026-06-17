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

    private String id;
    private String name;             // globally unique
    private String ownerId;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
