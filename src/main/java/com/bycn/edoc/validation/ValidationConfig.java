package com.bycn.edoc.validation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Câblage Spring de la validation (P4). */
@Configuration
@EnableConfigurationProperties(ValidationProperties.class)
public class ValidationConfig {

    /** Le registre porte le cache des tables : un seul bean partagé pour tout le run. */
    @Bean
    public ReferenceTableRegistry referenceTableRegistry() {
        return new ReferenceTableRegistry();
    }

    @Bean
    public FieldValidator fieldValidator(ReferenceTableRegistry registry, ValidationProperties properties) {
        return new FieldValidator(registry, properties);
    }
}
