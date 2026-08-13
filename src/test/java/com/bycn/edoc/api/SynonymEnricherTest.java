package com.bycn.edoc.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.bycn.edoc.classification.ClassificationProperties;
import com.bycn.edoc.classification.SchemaFieldsRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests adosses au VRAI schema_fields.yaml livre, comme les tests P3 : un enrichissement verifie
 * ici est un enrichissement qui aura vraiment lieu en production.
 */
class SynonymEnricherTest {

    private static SynonymEnricher enricher(boolean enabled) {
        return new SynonymEnricher(
                SchemaFieldsRegistry.fromClasspath(SchemaFieldsRegistry.DEFAULT_RESOURCE),
                new ClassificationProperties(80, false),
                enabled);
    }

    private static RequestedField field(String name, String... labels) {
        return new RequestedField(name, List.of(labels), List.of());
    }

    @Test
    void caller_labels_are_always_kept_and_come_first() {
        // Arrange : libelles tels que eDoc les configure (longLabel + shortLabel).
        RequestedField phase = field("spec_char1", "Phase", "Pha");

        // Act
        List<String> synonyms = enricher(true).synonymsFor(phase);

        // Assert : jamais remplaces, et toujours en tete.
        assertThat(synonyms).startsWith("Phase", "Pha");
    }

    @Test
    void a_known_field_gains_the_corpus_variants() {
        // eDoc ne connait que "Numero". Le cartouche, lui, ecrit "N° Doc", "N° GED", "N° Chrono"...
        // Ces variantes viennent du corpus reel et sont le vrai apport de l'enrichissement.
        List<String> synonyms = enricher(true).synonymsFor(field("spec_int1", "Numéro"));

        assertThat(synonyms).contains("Numéro", "N° Doc", "N° GED", "Num");
    }

    @Test
    void an_english_variant_of_the_corpus_is_reachable_from_the_french_label() {
        // "Level" est confirme sur le corpus pour NIVEAU : un projet qui ne configure que
        // "Niveau" doit malgre tout lire un cartouche anglophone.
        List<String> synonyms = enricher(true).synonymsFor(field("spec_char4", "Niveau"));

        assertThat(synonyms).contains("Level");
    }

    @Test
    void a_field_unknown_to_the_library_keeps_exactly_its_caller_labels() {
        // "CO/NB" est un champ specifique reel d'un projet eDoc, absent du yaml : il doit
        // fonctionner tel quel, sans etre contraint ni enrichi a tort.
        List<String> synonyms = enricher(true).synonymsFor(field("spec_char7", "CO/NB"));

        assertThat(synonyms).containsExactly("CO/NB");
    }

    @Test
    void enrichment_can_be_switched_off_without_touching_the_code() {
        // Heuristique non mesuree : elle doit rester debrayable par configuration.
        List<String> synonyms = enricher(false).synonymsFor(field("spec_int1", "Numéro"));

        assertThat(synonyms).containsExactly("Numéro");
    }

    @Test
    void a_field_without_any_usable_label_yields_no_synonym() {
        List<String> synonyms = enricher(true).synonymsFor(field("spec_char9", "", "   "));

        assertThat(synonyms).isEmpty();
    }

    @Test
    void synonyms_are_deduplicated() {
        // "Phase" est a la fois un libelle eDoc et un synonyme du yaml : il ne doit pas sortir deux fois.
        List<String> synonyms = enricher(true).synonymsFor(field("spec_char1", "Phase"));

        assertThat(synonyms).doesNotHaveDuplicates();
    }
}
