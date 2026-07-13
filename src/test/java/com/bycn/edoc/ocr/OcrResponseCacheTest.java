package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OcrResponseCacheTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode node(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void stores_then_serves_the_same_response_for_identical_request_bytes(@TempDir Path tmp) throws Exception {
        // Arrange
        OcrResponseCache cache = new OcrResponseCache(tmp, mapper);
        byte[] request = "the-exact-request-body".getBytes(StandardCharsets.UTF_8);
        JsonNode response = node("{\"document_annotation\":\"{}\",\"model\":\"mistral-ocr-4-0\"}");

        // Act
        assertThat(cache.get(request)).isEmpty(); // miss avant écriture
        cache.put(request, response);
        var served = cache.get(request); // hit après écriture

        // Assert
        assertThat(served).isPresent();
        assertThat(served.get()).isEqualTo(response);
        assertThat(cache.hits()).isEqualTo(1);
        assertThat(cache.misses()).isEqualTo(1);
    }

    @Test
    void different_request_bytes_do_not_collide(@TempDir Path tmp) throws Exception {
        OcrResponseCache cache = new OcrResponseCache(tmp, mapper);
        cache.put("request-A".getBytes(StandardCharsets.UTF_8), node("{\"a\":1}"));

        assertThat(cache.get("request-B".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(cache.get("request-A".getBytes(StandardCharsets.UTF_8)))
                .get().isEqualTo(node("{\"a\":1}"));
    }

    @Test
    void disabled_cache_never_stores_nor_serves() throws Exception {
        OcrResponseCache cache = OcrResponseCache.disabled();
        byte[] request = "anything".getBytes(StandardCharsets.UTF_8);

        cache.put(request, node("{\"a\":1}"));

        assertThat(cache.isEnabled()).isFalse();
        assertThat(cache.get(request)).isEmpty();
        // Un cache désactivé ne compte ni hit ni miss (il court-circuite avant tout compteur).
        assertThat(cache.hits()).isZero();
        assertThat(cache.misses()).isZero();
    }
}
