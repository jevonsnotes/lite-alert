package io.litealert.common.crypto;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds a dedicated {@link StringEncryptor} for at-rest field encryption,
 * configured from the same {@code jasypt.encryptor.*} properties that
 * jasypt-spring-boot uses for {@code ENC(...)} placeholders.
 *
 * <p>We don't reuse jasypt-spring-boot's bean directly because it's
 * registered lazily (only when an {@code ENC(...)} placeholder is actually
 * decrypted), which can race with our store-mapper construction.
 */
@Configuration
public class JasyptConfig {

    @Bean(name = "fieldEncryptor")
    public StringEncryptor fieldEncryptor(
            @Value("${jasypt.encryptor.password}") String password,
            @Value("${jasypt.encryptor.algorithm:PBEWITHHMACSHA512ANDAES_256}") String algorithm,
            @Value("${jasypt.encryptor.iv-generator-classname:org.jasypt.iv.RandomIvGenerator}") String ivGen,
            @Value("${jasypt.encryptor.salt-generator-classname:org.jasypt.salt.RandomSaltGenerator}") String saltGen,
            @Value("${jasypt.encryptor.pool-size:1}") int poolSize,
            @Value("${jasypt.encryptor.string-output-type:base64}") String outputType) {

        SimpleStringPBEConfig cfg = new SimpleStringPBEConfig();
        cfg.setPassword(password);
        cfg.setAlgorithm(algorithm);
        cfg.setIvGeneratorClassName(ivGen);
        cfg.setSaltGeneratorClassName(saltGen);
        cfg.setPoolSize(String.valueOf(poolSize));
        cfg.setStringOutputType(outputType);

        PooledPBEStringEncryptor enc = new PooledPBEStringEncryptor();
        enc.setConfig(cfg);
        return enc;
    }
}
