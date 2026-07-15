package com.bycn.edoc.classification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Câblage Spring de la classification (P3). */
@Configuration
@EnableConfigurationProperties(ClassificationProperties.class)
public class ClassificationConfig {

    /** La bibliothèque de synonymes est chargée une fois au démarrage, puis immuable. */
    @Bean
    public SchemaFieldsRegistry schemaFieldsRegistry() {
        return SchemaFieldsRegistry.fromClasspath(SchemaFieldsRegistry.DEFAULT_RESOURCE);
    }

    @Bean
    public FieldClassifier fieldClassifier(SchemaFieldsRegistry registry, ClassificationProperties properties) {
        return new FieldClassifier(registry, properties);
    }
}
