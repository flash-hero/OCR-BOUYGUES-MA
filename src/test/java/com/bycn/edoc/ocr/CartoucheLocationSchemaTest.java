package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CartoucheLocationSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void schema_asks_only_for_a_zone_not_for_field_contents() {
        JsonNode schema = CartoucheLocationSchema.schema(mapper);

        JsonNode props = schema.path("properties");
        assertThat(props.has("cartoucheFound")).isTrue();
        assertThat(props.has("corner")).isTrue();
        // Passe 1 : pas de champ de contenu (fields), on ne veut que la position.
        assertThat(props.has("fields")).isFalse();

        List<String> zones = new ArrayList<>();
        props.path("corner").path("enum").forEach(n -> zones.add(n.asText()));
        assertThat(zones).contains("bottom-right", "top-left", "bottom-center", "unknown");
    }

    @Test
    void format_wraps_the_schema_with_a_distinct_name() {
        JsonNode fmt = CartoucheLocationSchema.format(mapper);

        assertThat(fmt.path("type").asText()).isEqualTo("json_schema");
        assertThat(fmt.path("json_schema").path("name").asText()).isEqualTo("cartouche_location");
        assertThat(fmt.path("json_schema").path("strict").asBoolean()).isTrue();
    }
}
