package io.litealert.common.crypto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Jackson module that transparently encrypts/decrypts String fields annotated
 * with {@link Encrypted}. Used by the file-store ObjectMapper.
 *
 * <p>On serialize: {@code "raw"} → {@code "ENC:..."} (base64).<br>
 * On deserialize: {@code "ENC:..."} → {@code "raw"}; legacy plain values pass
 * through unchanged so old data files don't break after enabling encryption.
 */
@Slf4j
public class EncryptedFieldModule extends SimpleModule {

    /** Marker prefix to distinguish encrypted blobs from legacy plain text. */
    public static final String PREFIX = "ENC:";

    public EncryptedFieldModule(StringEncryptor encryptor) {
        super("EncryptedFieldModule");
        setSerializerModifier(new EncryptedSerializerModifier(encryptor));
        setDeserializerModifier(new EncryptedDeserializerModifier(encryptor));
    }

    @RequiredArgsConstructor
    static class EncryptedStringSerializer extends JsonSerializer<String> {
        private final StringEncryptor encryptor;

        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            try {
                gen.writeString(PREFIX + encryptor.encrypt(value));
            } catch (Exception e) {
                log.error("encrypt failed, writing as null to avoid leaking", e);
                gen.writeNull();
            }
        }
    }

    @RequiredArgsConstructor
    static class EncryptedStringDeserializer extends JsonDeserializer<String> {
        private final StringEncryptor encryptor;

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String raw = p.getValueAsString();
            if (raw == null) return null;
            if (raw.startsWith(PREFIX)) {
                try {
                    return encryptor.decrypt(raw.substring(PREFIX.length()));
                } catch (Exception e) {
                    throw new IOException("failed to decrypt field; wrong jasypt password?", e);
                }
            }
            // legacy plain text — leave as is
            return raw;
        }
    }

    @RequiredArgsConstructor
    static class EncryptedSerializerModifier
            extends com.fasterxml.jackson.databind.ser.BeanSerializerModifier {
        private final StringEncryptor encryptor;

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter>
        changeProperties(com.fasterxml.jackson.databind.SerializationConfig config,
                         com.fasterxml.jackson.databind.BeanDescription beanDesc,
                         java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> beanProperties) {
            for (int i = 0; i < beanProperties.size(); i++) {
                com.fasterxml.jackson.databind.ser.BeanPropertyWriter w = beanProperties.get(i);
                if (w.getAnnotation(Encrypted.class) != null
                        && String.class.equals(w.getType().getRawClass())) {
                    JsonSerializer ser = new EncryptedStringSerializer(encryptor);
                    w.assignSerializer(ser);
                }
            }
            return beanProperties;
        }
    }

    @RequiredArgsConstructor
    static class EncryptedDeserializerModifier
            extends com.fasterxml.jackson.databind.deser.BeanDeserializerModifier {
        private final StringEncryptor encryptor;

        @Override
        public com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder updateBuilder(
                com.fasterxml.jackson.databind.DeserializationConfig config,
                com.fasterxml.jackson.databind.BeanDescription beanDesc,
                com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder builder) {
            var props = builder.getProperties();
            while (props.hasNext()) {
                var prop = props.next();
                if (prop.getAnnotation(Encrypted.class) != null
                        && String.class.equals(prop.getType().getRawClass())) {
                    builder.addOrReplaceProperty(
                            prop.withValueDeserializer(new EncryptedStringDeserializer(encryptor)),
                            true);
                }
            }
            return builder;
        }
    }

    /**
     * Wires the module into a dedicated ObjectMapper used only by the file
     * store, so JSON returned to the HTTP layer (where we never want to leak
     * decrypted secrets) goes through a different mapper.
     */
    @Configuration
    public static class StoreObjectMapperConfig {

        @Bean(name = "storeObjectMapper")
        public com.fasterxml.jackson.databind.ObjectMapper storeObjectMapper(
                @Qualifier("fieldEncryptor") StringEncryptor encryptor) {
            com.fasterxml.jackson.databind.ObjectMapper m =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            m.disable(com.fasterxml.jackson.databind.SerializationFeature
                    .WRITE_DATES_AS_TIMESTAMPS);
            m.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            m.registerModule(new EncryptedFieldModule(encryptor));
            return m;
        }
    }
}
