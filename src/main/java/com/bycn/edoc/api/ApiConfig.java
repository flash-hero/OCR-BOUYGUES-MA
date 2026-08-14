package com.bycn.edoc.api;

import com.bycn.edoc.classification.ClassificationProperties;
import com.bycn.edoc.classification.FieldClassifier;
import com.bycn.edoc.classification.SchemaFieldsRegistry;
import com.bycn.edoc.ocr.TwoPassCartoucheExtractor;
import com.bycn.edoc.validation.FieldValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Câblage Spring de l'API d'extraction (P5). */
@Configuration
@EnableConfigurationProperties(ApiProperties.class)
public class ApiConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiConfig.class);

    private final ApiProperties properties;

    public ApiConfig(ApiProperties properties) {
        this.properties = properties;
    }

    /**
     * Un service ouvert n'est acceptable qu'en développement local. Le signaler au démarrage évite
     * qu'il se retrouve exposé sans que personne ne s'en aperçoive.
     */
    @PostConstruct
    void warnIfUnprotected() {
        if (!properties.hasKey()) {
            log.warn("API d'extraction SANS clé (edoc.api.key vide) : tout appelant joignable peut "
                    + "consommer des appels Mistral. Acceptable en local uniquement.");
        }
    }

    @Bean
    public SynonymEnricher synonymEnricher(SchemaFieldsRegistry registry, ClassificationProperties properties) {
        return new SynonymEnricher(registry, properties, this.properties.enrichSynonyms());
    }

    @Bean
    public ExtractionService extractionService(TwoPassCartoucheExtractor extractor,
                                               FieldClassifier classifier,
                                               FieldValidator validator,
                                               SynonymEnricher enricher) {
        return new ExtractionService(extractor, classifier, validator, enricher);
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter() {
        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(properties));
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
