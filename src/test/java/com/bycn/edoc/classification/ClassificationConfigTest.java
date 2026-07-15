package com.bycn.edoc.classification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifie le cablage Spring sans demarrer l'application entiere : pas de cle API, pas de reseau.
 * Sans ce test, une erreur de binding ou une ressource introuvable ne se verrait qu'au demarrage.
 */
class ClassificationConfigTest {

    // ClassificationConfig porte @EnableConfigurationProperties : le binding est cable par lui.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClassificationConfig.class);

    @Test
    void wires_the_classifier_and_loads_the_library_from_the_classpath() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FieldClassifier.class);
            assertThat(context.getBean(SchemaFieldsRegistry.class).size()).isEqualTo(14);
        });
    }

    @Test
    void defaults_are_threshold_80_and_hypothesis_disabled() {
        runner.run(context -> {
            ClassificationProperties props = context.getBean(ClassificationProperties.class);
            assertThat(props.fuzzyThreshold()).isEqualTo(80);
            assertThat(props.useHypothesisSynonyms()).isFalse();
        });
    }

    @Test
    void both_settings_are_overridable_from_configuration() {
        // Le seuil est PROVISOIRE (non calibre) : il doit rester surchargeable, jamais fige.
        runner.withPropertyValues(
                        "edoc.classification.fuzzy-threshold=65",
                        "edoc.classification.use-hypothesis-synonyms=true")
                .run(context -> {
                    ClassificationProperties props = context.getBean(ClassificationProperties.class);
                    assertThat(props.fuzzyThreshold()).isEqualTo(65);
                    assertThat(props.useHypothesisSynonyms()).isTrue();
                });
    }
}
