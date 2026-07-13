package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MistralOcrClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private MistralOcrProperties props() {
        return new MistralOcrProperties(
                "test-key", "https://api.mistral.ai", "/v1/ocr", "mistral-ocr-4-0", 8, true, ".ocr-cache");
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    @Test
    void analyze_image_sends_pinned_model_open_schema_and_image_url_then_parses_string_annotation() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,"
                + "\"fields\":[{\"label\":\"N° de plan\",\"value\":\"PL-001\"}]}";
        String responseJson = "{\"model\":\"mistral-ocr-4-0\",\"document_annotation\":"
                + jsonString(annotation) + "}";

        server.expect(requestTo("https://api.mistral.ai/v1/ocr"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("mistral-ocr-4-0"))
                .andExpect(jsonPath("$.document.type").value("image_url"))
                .andExpect(jsonPath("$.document.image_url").value(Matchers.startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.document_annotation_format.type").value("json_schema"))
                .andExpect(jsonPath("$.document_annotation_format.json_schema.name").value("cartouche_extraction"))
                .andExpect(jsonPath("$.include_image_base64").value(false))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        MistralOcrClient client = new MistralOcrClient(props(), builder, mapper);

        // Act
        OcrResult result = client.analyzeImage(PNG);

        // Assert
        server.verify();
        assertThat(result.hasAnnotation()).isTrue();
        assertThat(result.extraction().cartoucheFound()).isTrue();
        assertThat(result.extraction().fields()).singleElement().satisfies(f -> {
            assertThat(f.label()).isEqualTo("N° de plan");
            assertThat(f.value()).isEqualTo("PL-001");
        });
    }

    @Test
    void locate_image_sends_location_schema_over_image_url_and_parses_the_returned_zone() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,\"corner\":\"bottom-right\"}";
        String responseJson = "{\"document_annotation\":" + jsonString(annotation) + "}";

        server.expect(requestTo("https://api.mistral.ai/v1/ocr"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.document.type").value("image_url"))
                .andExpect(jsonPath("$.document_annotation_format.json_schema.name").value("cartouche_location"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        MistralOcrClient client = new MistralOcrClient(props(), builder, mapper);

        CartoucheLocation location = client.locateImage(PNG);

        server.verify();
        assertThat(location.cartoucheFound()).isTrue();
        assertThat(location.corner()).isEqualTo("bottom-right");
        assertThat(location.isUnknown()).isFalse();
    }

    @Test
    void second_identical_call_is_served_from_cache_without_hitting_the_server(@TempDir Path tmp) {
        // Arrange : le serveur n'attend QU'UN seul appel ; le second doit venir du cache.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"A\",\"value\":\"1\"}]}";
        String responseJson = "{\"document_annotation\":" + jsonString(annotation) + "}";
        server.expect(ExpectedCount.once(), requestTo("https://api.mistral.ai/v1/ocr"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        OcrResponseCache cache = new OcrResponseCache(tmp.resolve("cache"), mapper);
        MistralOcrClient client = new MistralOcrClient(props(), builder, mapper, cache);

        // Act : deux appels identiques (mêmes octets d'image).
        OcrResult first = client.analyzeImage(PNG);
        OcrResult second = client.analyzeImage(PNG);

        // Assert : un seul appel réseau, réponses identiques, un hit compté.
        server.verify();
        assertThat(first.extraction().fields()).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("1"));
        assertThat(second.extraction().fields()).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("1"));
        assertThat(cache.hits()).isEqualTo(1);
        assertThat(cache.misses()).isEqualTo(1);
    }

    @Test
    void wraps_http_error_into_readable_exception() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.mistral.ai/v1/ocr"))
                .andRespond(withServerError().body("boom"));

        MistralOcrClient client = new MistralOcrClient(props(), builder, mapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.analyzeImage(PNG))
                .isInstanceOf(MistralOcrException.class)
                .hasMessageContaining("500");
    }

    private String jsonString(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
