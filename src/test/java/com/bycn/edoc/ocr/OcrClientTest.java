package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OcrClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Propriétés à échantillon unique (samples=1) : comportement d'un seul appel, pour les tests de forme. */
    private OcrProperties props() {
        return props(1);
    }

    /** Forme historique {@code /v1/ocr} (Mistral Document AI). */
    private OcrProperties props(int samples) {
        return new OcrProperties(
                "test-key", "https://api.mistral.ai", "/v1/ocr", "mistral-ocr-4-0", 8, true, ".ocr-cache",
                samples, 0, OcrApiFlavor.OCR, "/openai/v1/chat/completions", "none", 8000, 0, 7, "high", "low");
    }

    /** Forme {@code chat/completions} (déploiement gpt-5.5 sur Azure AI Foundry). */
    private OcrProperties chatProps(int samples) {
        return new OcrProperties(
                "test-key", "https://scanbeton.services.ai.azure.com", "/v1/ocr", "gpt-5.5", 8, true,
                ".ocr-cache", samples, 0, OcrApiFlavor.CHAT, "/openai/v1/chat/completions", "none", 8000,
                0, 7, "high", "low");
    }

    /** Enveloppe une annotation dans la forme d'une réponse de chat. */
    private String chatResponse(String annotation) {
        return "{\"model\":\"gpt-5.5-2026-04-24\",\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":" + jsonString(annotation) + "}}]}";
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

        OcrClient client = new OcrClient(props(), builder, mapper);

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

        OcrClient client = new OcrClient(props(), builder, mapper);

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
        OcrClient client = new OcrClient(props(), builder, mapper, cache);

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
    void samples_three_times_and_returns_the_median_pair_count_then_caches_the_consensus(@TempDir Path tmp) {
        // Arrange : 3 echantillons du MEME appel renvoient 1, 3 puis 2 paires. Le consensus doit
        // retenir la mediane (2 paires), sans fusionner ni filtrer, et figer ce choix dans le cache.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String a = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"A\",\"value\":\"1\"}]}";
        String b = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"B1\",\"value\":\"1\"},"
                + "{\"label\":\"B2\",\"value\":\"2\"},{\"label\":\"B3\",\"value\":\"3\"}]}";
        String c = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"C1\",\"value\":\"1\"},"
                + "{\"label\":\"C2\",\"value\":\"2\"}]}";
        for (String annotation : new String[] {a, b, c}) {
            String responseJson = "{\"document_annotation\":" + jsonString(annotation) + "}";
            server.expect(ExpectedCount.once(), requestTo("https://api.mistral.ai/v1/ocr"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));
        }

        OcrResponseCache cache = new OcrResponseCache(tmp.resolve("cache"), mapper);
        // Exécuteur synchrone : les 3 échantillons partent dans l'ordre sur le thread appelant, ce qui
        // garde MockRestServiceServer (non thread-safe) déterministe tout en exerçant le consensus.
        OcrClient client = new OcrClient(props(3), builder, mapper, cache, (Runnable r) -> r.run());

        // Act : un appel logique -> 3 appels reseau.
        OcrResult result = client.analyzeImage(PNG);

        // Assert : mediane (2 paires) retenue, verbatim ; 3 appels reels comptes.
        server.verify();
        assertThat(client.networkCalls()).isEqualTo(3);
        assertThat(result.extraction().fields()).hasSize(2);
        assertThat(result.extraction().fields().get(0).label()).isEqualTo("C1");

        // Un second appel identique est resservi depuis le cache : aucun nouvel appel reseau.
        OcrResult again = client.analyzeImage(PNG);
        assertThat(client.networkCalls()).isEqualTo(3);
        assertThat(again.extraction().fields()).hasSize(2);
        assertThat(cache.hits()).isEqualTo(1);
    }

    @Test
    void one_malformed_sample_does_not_fail_the_document_consensus_uses_the_others(@TempDir Path tmp) {
        // 13.pdf : un échantillon renvoie une annotation JSON tronquée. Il doit être ignoré, le vote se
        // faisant sur les échantillons valides — jamais faire échouer le document pour un mauvais échantillon.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String broken = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"A\","; // JSON tronqué
        String good = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"PHASE\",\"value\":\"EXE\"},"
                + "{\"label\":\"INDICE\",\"value\":\"G\"}]}";
        for (String annotation : new String[] {broken, good, good}) {
            String responseJson = "{\"document_annotation\":" + jsonString(annotation) + "}";
            server.expect(ExpectedCount.once(), requestTo("https://api.mistral.ai/v1/ocr"))
                    .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));
        }

        OcrResponseCache cache = new OcrResponseCache(tmp.resolve("cache"), mapper);
        OcrClient client = new OcrClient(props(3), builder, mapper, cache, (Runnable r) -> r.run());

        OcrResult result = client.analyzeImage(PNG);

        server.verify();
        // Le document survit : consensus sur les 2 échantillons valides (2 paires), pas d'exception.
        assertThat(result.extraction().cartoucheFound()).isTrue();
        assertThat(result.extraction().fields()).hasSize(2);
    }

    @Test
    void chat_flavor_posts_the_deployment_the_schema_the_image_and_no_reasoning_budget() {
        // Arrange : meme schema ouvert, mais enveloppe chat/completions — l'image part comme contenu
        // de message et le schema comme response_format.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,"
                + "\"fields\":[{\"label\":\"N° de plan\",\"value\":\"PL-001\"}]}";

        server.expect(requestTo("https://scanbeton.services.ai.azure.com/openai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-key"))
                .andExpect(jsonPath("$.model").value("gpt-5.5"))
                // Pas de reflexion : la tache est un pur report de ce qui est imprime.
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$.max_completion_tokens").value(8000))
                // Determinisme : accepte par l'endpoint chat, contrairement a /v1/ocr.
                .andExpect(jsonPath("$.temperature").value(0.0))
                .andExpect(jsonPath("$.seed").value(7))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("cartouche_extraction"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                // Consigne de role : uniquement ce qui est lisible, reponse immediate, JSON nu.
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(Matchers.containsString("RÉPONDS IMMÉDIATEMENT")))
                .andExpect(jsonPath("$.messages[1].content[0].type").value("text"))
                .andExpect(jsonPath("$.messages[1].content[1].image_url.url")
                        .value(Matchers.startsWith("data:image/png;base64,")))
                // Pleine resolution : les caracteres d'un cartouche sont petits.
                .andExpect(jsonPath("$.messages[1].content[1].image_url.detail").value("high"))
                // L'enveloppe /v1/ocr ne doit plus etre envoyee.
                .andExpect(jsonPath("$.document").doesNotExist())
                .andExpect(jsonPath("$.document_annotation_format").doesNotExist())
                .andRespond(withSuccess(chatResponse(annotation), MediaType.APPLICATION_JSON));

        OcrClient client = new OcrClient(chatProps(1), builder, mapper);

        // Act
        OcrResult result = client.analyzeImage(PNG);

        // Assert : l'annotation est lue dans choices[0].message.content, verbatim.
        server.verify();
        assertThat(result.extraction().cartoucheFound()).isTrue();
        assertThat(result.extraction().fields()).singleElement().satisfies(f -> {
            assertThat(f.label()).isEqualTo("N° de plan");
            assertThat(f.value()).isEqualTo("PL-001");
        });
    }

    @Test
    void chat_flavor_locates_with_the_location_schema_and_reads_the_zone_from_the_message() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,\"corner\":\"bottom-right\"}";
        server.expect(requestTo("https://scanbeton.services.ai.azure.com/openai/v1/chat/completions"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("cartouche_location"))
                .andRespond(withSuccess(chatResponse(annotation), MediaType.APPLICATION_JSON));

        OcrClient client = new OcrClient(chatProps(1), builder, mapper);

        CartoucheLocation location = client.locateImage(PNG);

        server.verify();
        assertThat(location.cartoucheFound()).isTrue();
        assertThat(location.corner()).isEqualTo("bottom-right");
    }

    @Test
    void locate_parses_the_bounding_box_and_the_corrective_rotation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,\"corner\":\"right\","
                + "\"box\":{\"left\":0.86,\"top\":0.05,\"right\":0.99,\"bottom\":0.95},"
                + "\"rotation\":\"90-cw\"}";
        server.expect(requestTo("https://scanbeton.services.ai.azure.com/openai/v1/chat/completions"))
                .andRespond(withSuccess(chatResponse(annotation), MediaType.APPLICATION_JSON));

        OcrClient client = new OcrClient(chatProps(1), builder, mapper);

        CartoucheLocation location = client.locateImage(PNG);

        server.verify();
        assertThat(location.hasBox()).isTrue();
        assertThat(location.box().x()).isEqualTo(0.86);
        assertThat(location.box().w()).isCloseTo(0.13, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(location.rotation()).isEqualTo("90-cw");
    }

    @Test
    void locate_rejects_a_degenerate_box_but_keeps_the_zone() {
        // Bords inverses : la boite est inexploitable, la zone reste utile au repli par coins.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,\"corner\":\"bottom-right\","
                + "\"box\":{\"left\":0.9,\"top\":0.9,\"right\":0.3,\"bottom\":0.95},"
                + "\"rotation\":\"none\"}";
        server.expect(requestTo("https://scanbeton.services.ai.azure.com/openai/v1/chat/completions"))
                .andRespond(withSuccess(chatResponse(annotation), MediaType.APPLICATION_JSON));

        OcrClient client = new OcrClient(chatProps(1), builder, mapper);

        CartoucheLocation location = client.locateImage(PNG);

        server.verify();
        assertThat(location.hasBox()).isFalse();
        assertThat(location.corner()).isEqualTo("bottom-right");
    }

    @Test
    void with_targets_the_request_carries_the_assignment_contract_and_the_answer_is_parsed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,"
                + "\"fields\":[{\"label\":\"NUMERO DE DOCUMENT\",\"value\":\"PRORFR120\"}],"
                + "\"assignments\":[{\"field\":\"numero\",\"label\":\"NUMERO DE DOCUMENT\","
                + "\"value\":\"PRORFR120\"}]}";
        server.expect(requestTo("https://scanbeton.services.ai.azure.com/openai/v1/chat/completions"))
                // Le schema contraint les noms de champ a la liste demandee (enum).
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.assignments"
                        + ".items.properties.field.enum[0]").value("numero"))
                // Le prompt liste le champ et ses libelles possibles.
                .andExpect(jsonPath("$.messages[1].content[0].text")
                        .value(Matchers.containsString("- numero (libellés possibles : Numéro)")))
                .andRespond(withSuccess(chatResponse(annotation), MediaType.APPLICATION_JSON));

        OcrClient client = new OcrClient(chatProps(1), builder, mapper);

        OcrResult result = client.analyzeImage(PNG,
                java.util.List.of(new ReadTarget("numero", java.util.List.of("Numéro"))));

        server.verify();
        assertThat(result.extraction().fields()).hasSize(1);
        assertThat(result.extraction().assignments()).singleElement().satisfies(a -> {
            assertThat(a.field()).isEqualTo("numero");
            assertThat(a.value()).isEqualTo("PRORFR120");
        });
    }

    @Test
    void chat_answer_wrapped_in_a_code_fence_is_still_parsed() {
        // La consigne interdit la cloture de code et le schema strict la rend improbable, mais un
        // echantillon sur N peut la produire : mieux vaut le recuperer que le perdre au parsing.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String fenced = "```json\n{\"cartoucheFound\":true,"
                + "\"fields\":[{\"label\":\"PHASE\",\"value\":\"EXE\"}]}\n```";
        server.expect(requestTo("https://scanbeton.services.ai.azure.com/openai/v1/chat/completions"))
                .andRespond(withSuccess(chatResponse(fenced), MediaType.APPLICATION_JSON));

        OcrClient client = new OcrClient(chatProps(1), builder, mapper);

        OcrResult result = client.analyzeImage(PNG);

        server.verify();
        assertThat(result.extraction().fields()).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("EXE"));
    }

    @Test
    void chat_flavor_tolerates_a_truncated_sample_and_votes_on_the_others(@TempDir Path tmp) {
        // Meme garantie qu'en /v1/ocr : un echantillon coupe (finish_reason=length) est ignore,
        // jamais une exception qui ferait echouer tout le document.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String truncated = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"A\",";
        String good = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"PHASE\",\"value\":\"EXE\"},"
                + "{\"label\":\"INDICE\",\"value\":\"G\"}]}";
        for (String annotation : new String[] {truncated, good, good}) {
            server.expect(ExpectedCount.once(),
                            requestTo("https://scanbeton.services.ai.azure.com/openai/v1/chat/completions"))
                    .andRespond(withSuccess(chatResponse(annotation), MediaType.APPLICATION_JSON));
        }

        OcrResponseCache cache = new OcrResponseCache(tmp.resolve("cache"), mapper);
        OcrClient client = new OcrClient(chatProps(3), builder, mapper, cache, (Runnable r) -> r.run());

        OcrResult result = client.analyzeImage(PNG);

        server.verify();
        assertThat(result.extraction().cartoucheFound()).isTrue();
        assertThat(result.extraction().fields()).hasSize(2);
    }

    @Test
    void analyze_text_sends_the_document_text_and_no_image_at_all() {
        // Voie principale : le texte vient du PDF, exact. Aucune image ne doit partir — c'est tout
        // l'interet (pas de transcription, donc pas d'erreur de transcription, et bien plus rapide).
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String annotation = "{\"cartoucheFound\":true,"
                + "\"fields\":[{\"label\":\"PHASE\",\"value\":\"EXE\"},{\"label\":\"LOT\",\"value\":\"36\"}],"
                + "\"assignments\":[{\"field\":\"lot\",\"label\":\"LOT\",\"value\":\"36\"}]}";

        server.expect(requestTo("https://scanbeton.services.ai.azure.com/openai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("gpt-5.5"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("cartouche_extraction"))
                // Le texte du document part dans le message utilisateur...
                .andExpect(jsonPath("$.messages[1].content")
                        .value(Matchers.containsString("PROJET PHASE EMETTEUR LOT")))
                // ...et aucune image n'accompagne la requete.
                .andExpect(jsonPath("$.messages[1].content[0]").doesNotExist())
                .andRespond(withSuccess(chatResponse(annotation), MediaType.APPLICATION_JSON));

        OcrClient client = new OcrClient(chatProps(1), builder, mapper);

        OcrResult result = client.analyzeText(
                "PROJET PHASE EMETTEUR LOT\nHUA EXE TRA 36",
                java.util.List.of(new ReadTarget("lot", java.util.List.of("Lot"))));

        server.verify();
        assertThat(result.extraction().fields()).hasSize(2);
        assertThat(result.extraction().assignments()).singleElement()
                .satisfies(a -> assertThat(a.value()).isEqualTo("36"));
    }

    @Test
    void a_gateway_timeout_is_retried_rather_than_failing_the_document() {
        // 504 observe en conditions reelles sur un document du corpus : la passerelle abandonne
        // avant le service. Sans nouvelle tentative, le depot echoue cote utilisateur alors que le
        // meme document passe a l'essai suivant.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(ExpectedCount.once(), requestTo("https://api.mistral.ai/v1/ocr"))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).body("gateway timeout"));
        String annotation = "{\"cartoucheFound\":true,\"fields\":[{\"label\":\"PHASE\",\"value\":\"EXE\"}]}";
        server.expect(ExpectedCount.once(), requestTo("https://api.mistral.ai/v1/ocr"))
                .andRespond(withSuccess("{\"document_annotation\":" + jsonString(annotation) + "}",
                        MediaType.APPLICATION_JSON));

        // maxRetries = 1 : un seul reessai suffit a demontrer le comportement.
        OcrProperties retrying = new OcrProperties(
                "test-key", "https://api.mistral.ai", "/v1/ocr", "mistral-ocr-4-0", 8, true, ".ocr-cache",
                1, 1, OcrApiFlavor.OCR, "/openai/v1/chat/completions", "none", 8000, 0, 7, "high", "low");
        OcrClient client = new OcrClient(retrying, builder, mapper);

        OcrResult result = client.analyzeImage(PNG);

        server.verify();
        assertThat(result.extraction().fields()).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("EXE"));
    }

    @Test
    void wraps_http_error_into_readable_exception() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.mistral.ai/v1/ocr"))
                .andRespond(withServerError().body("boom"));

        OcrClient client = new OcrClient(props(), builder, mapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.analyzeImage(PNG))
                .isInstanceOf(OcrException.class)
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
