package io.litealert.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Wraps networknt's JsonSchemaFactory. Schemas are compiled per-topic and
 * could be cached, but since topics are few we recompile on demand for
 * simplicity (and to honor in-place schema edits while DRAFT).
 */
@Component
public class JsonSchemaService {

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public void validate(JsonNode schemaNode, JsonNode payload) {
        if (schemaNode == null) return;
        JsonSchema schema = factory.getSchema(schemaNode);
        Set<ValidationMessage> errors = schema.validate(payload);
        if (errors.isEmpty()) return;
        List<BusinessException.FieldError> details = errors.stream()
                .map(e -> new BusinessException.FieldError(e.getInstanceLocation().toString(), e.getMessage()))
                .toList();
        throw new BusinessException(ErrorCode.SCHEMA_VALIDATION_FAILED,
                "payload does not match topic schema", details);
    }
}
