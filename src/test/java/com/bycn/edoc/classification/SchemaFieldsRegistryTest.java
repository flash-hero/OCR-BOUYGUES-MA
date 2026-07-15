package com.bycn.edoc.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaFieldsRegistryTest {

    private static final SchemaFieldsRegistry REGISTRY =
            SchemaFieldsRegistry.fromClasspath(SchemaFieldsRegistry.DEFAULT_RESOURCE);

    @Test
    void loads_every_target_field_of_the_shared_library() {
        assertThat(REGISTRY.size()).isEqualTo(14);
        assertThat(REGISTRY.fieldNames()).contains(
                "PHASE", "EMETTEUR", "LOT", "TYPE", "ZONE", "NIVEAU", "NUMERO",
                "Indice", "Titre1", "Titre2", "Titre3", "Batiment",
                "FORMAT_DU_PLAN", "ECHELLE_DU_PLAN");
    }

    @Test
    void reads_confirmed_and_hypothesis_lists_separately() {
        SynonymEntry lot = REGISTRY.find("LOT").orElseThrow();
        assertThat(lot.confirmed()).containsExactly("Lot");
        assertThat(lot.hypothesis()).containsExactly("Spécialité", "Specialite", "Discipline");
    }

    @Test
    void keeps_accented_synonyms_intact() {
        // Garde-fou d'encodage : le YAML est lu en UTF-8, les accents ne doivent pas etre mangles.
        assertThat(REGISTRY.find("EMETTEUR").orElseThrow().confirmed())
                .containsExactly("Emetteur", "Émetteur", "Emet");
        assertThat(REGISTRY.find("Batiment").orElseThrow().confirmed())
                .containsExactly("Bâtiment", "Batiment");
    }

    @Test
    void quoted_synonyms_with_degree_sign_are_preserved() {
        assertThat(REGISTRY.find("NUMERO").orElseThrow().confirmed())
                .containsExactly("N° Doc", "N°Doc", "N° GED", "N° Chrono", "N° document",
                        "Num", "Numero", "Numéro");
    }

    @Test
    void field_declared_without_synonyms_yields_empty_lists_not_null() {
        SynonymEntry format = REGISTRY.find("FORMAT_DU_PLAN").orElseThrow();
        assertThat(format.confirmed()).isEmpty();
        assertThat(format.hypothesis()).isEmpty();
    }

    @Test
    void target_field_lookup_is_exact_never_fuzzy() {
        // Le flou s'applique au libelle lu, jamais au nom du champ demande par l'appel API.
        assertThat(REGISTRY.find("NUMERO")).isPresent();
        assertThat(REGISTRY.find("numero")).isEmpty();
        assertThat(REGISTRY.find("NUMER0")).isEmpty();
        assertThat(REGISTRY.find("CHAMP_INEXISTANT")).isEmpty();
    }

    @Test
    void active_synonyms_include_hypothesis_only_when_enabled() {
        SynonymEntry type = REGISTRY.find("TYPE").orElseThrow();
        assertThat(type.activeSynonyms(false)).containsExactly("Type", "Typ");
        assertThat(type.activeSynonyms(true)).containsExactly("Type", "Typ", "Doc");
    }

    @Test
    void synonym_entry_defends_against_null_lists() {
        SynonymEntry entry = new SynonymEntry(null, null);
        assertThat(entry.confirmed()).isEmpty();
        assertThat(entry.hypothesis()).isEmpty();
        assertThat(entry.activeSynonyms(true)).isEmpty();
    }

    @Test
    void malformed_yaml_without_fields_key_is_rejected() {
        assertThat(List.of("autre_cle: []", "", "fields: 42")).allSatisfy(yaml ->
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                                SchemaFieldsRegistry.fromYaml(
                                        new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                        .isInstanceOf(IllegalStateException.class));
    }
}
