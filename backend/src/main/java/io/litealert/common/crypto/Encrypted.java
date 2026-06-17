package io.litealert.common.crypto;

import com.fasterxml.jackson.annotation.JacksonAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a String field whose value should be transparently
 * Jasypt-encrypted when serialized to disk and decrypted when read back.
 *
 * <p>Only handles in-flight values; the rest of the JSON (keys, structure)
 * is plain text by design (see docs/design/02-data-model.md §2).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@JacksonAnnotation
public @interface Encrypted {
}
