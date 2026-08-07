package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CartoucheAnnotationSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void schema_declares_only_generic_label_value_pairs_and_no_fixed_business_field() {
        JsonNode schema = CartoucheAnnotationSchema.schema(mapper);

        assertThat(schema.path("type").asText()).isEqualTo("object");

        JsonNode props = schema.path("properties");
        assertThat(props.has("cartoucheFound")).isTrue();
        assertThat(props.has("fields")).isTrue();

        // Principe ÉA1 : aucun champ métier nommé à l'avance dans le schéma.
        assertThat(props.has("phase")).isFalse();
        assertThat(props.has("emetteur")).isFalse();
        assertThat(props.has("lot")).isFalse();

        JsonNode item = props.path("fields").path("items");
        assertThat(item.path("type").asText()).isEqualTo("object");
        assertThat(item.path("properties").has("label")).isTrue();
        assertThat(item.path("properties").has("value")).isTrue();
    }

    @Test
    void format_wraps_the_schema_as_a_json_schema_response_format() {
        JsonNode fmt = CartoucheAnnotationSchema.format(mapper);

        assertThat(fmt.path("type").asText()).isEqualTo("json_schema");
        assertThat(fmt.path("json_schema").path("name").asText()).isEqualTo("cartouche_extraction");
        assertThat(fmt.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(fmt.path("json_schema").path("schema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void with_targets_the_schema_adds_assignments_constrained_to_the_requested_names() {
        var targets = java.util.List.of(
                new ReadTarget("numero", java.util.List.of("Numéro", "N°")),
                new ReadTarget("phase", java.util.List.of("Phase")));

        JsonNode schema = CartoucheAnnotationSchema.schema(mapper, targets);

        JsonNode assignments = schema.path("properties").path("assignments");
        assertThat(assignments.path("type").asText()).isEqualTo("array");
        JsonNode item = assignments.path("items");
        assertThat(item.path("properties").has("field")).isTrue();
        assertThat(item.path("properties").has("label")).isTrue();
        assertThat(item.path("properties").has("value")).isTrue();

        // Liste fermée : le modèle ne peut pas inventer un nom de champ.
        java.util.List<String> names = new java.util.ArrayList<>();
        item.path("properties").path("field").path("enum").forEach(n -> names.add(n.asText()));
        assertThat(names).containsExactly("numero", "phase");

        // Et le contrat générique ne bouge pas : fields reste la lecture complète, non filtrée.
        assertThat(schema.path("properties").has("fields")).isTrue();
    }

    @Test
    void without_targets_neither_schema_nor_prompt_mention_assignments() {
        JsonNode schema = CartoucheAnnotationSchema.schema(mapper);
        assertThat(schema.path("properties").has("assignments")).isFalse();
        assertThat(CartoucheAnnotationSchema.promptFor(java.util.List.of()))
                .isEqualTo(CartoucheAnnotationSchema.PROMPT);
    }

    @Test
    void with_targets_the_prompt_lists_each_field_with_its_expected_labels() {
        var targets = java.util.List.of(new ReadTarget("numero", java.util.List.of("Numéro", "N°")));

        String prompt = CartoucheAnnotationSchema.promptFor(targets);

        assertThat(prompt).startsWith(CartoucheAnnotationSchema.PROMPT);
        assertThat(prompt).contains("assignments");
        assertThat(prompt).contains("- numero (libellés possibles : Numéro, N°)");
        // La garde est annoncée au modèle : jamais une valeur absente de fields.
        assertThat(prompt).contains("ne figure pas dans fields");
    }
}
