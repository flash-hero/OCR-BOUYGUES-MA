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
}
