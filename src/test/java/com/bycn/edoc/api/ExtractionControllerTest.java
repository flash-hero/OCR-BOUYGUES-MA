package com.bycn.edoc.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bycn.edoc.classification.FieldStatus;
import com.bycn.edoc.ocr.OcrException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Contrat HTTP de l'API. Montage "standalone" (pas de contexte Spring complet) : ces tests
 * verifient le contrat d'entree/sortie du controleur, pas le cablage, et restent instantanes.
 */
class ExtractionControllerTest {

    private ExtractionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ExtractionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ExtractionController(service, new ObjectMapper()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockMultipartFile document() {
        return new MockMultipartFile("file", "16.pdf", "application/pdf",
                "pdf-factice".getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile requestPart(String json) {
        return new MockMultipartFile("request", "", "application/json",
                json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_valid_call_returns_one_result_per_requested_field() throws Exception {
        // Arrange
        when(service.extract(any(), any())).thenReturn(new ExtractionResponse(
                true, "TWO_PASS_CROP", "bottom-right", true,
                List.of(new ExtractedField("spec_char1", "EXE", FieldStatus.AUTO_VALIDATED,
                        "Phase", 100, "EXE", "Exécution", 100.0)),
                List.of(new ExtractedPair("Echelle", "1/50")), 1234));

        // Act + Assert
        mockMvc.perform(multipart("/api/v1/extractions")
                        .file(document())
                        .file(requestPart("""
                                {"projectCode":"240716tdr","fields":[
                                  {"name":"spec_char1","labels":["Phase"],
                                   "allowedValues":[{"code":"EXE","libelle":"Exécution"}]}]}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartoucheFound").value(true))
                .andExpect(jsonPath("$.mode").value("TWO_PASS_CROP"))
                .andExpect(jsonPath("$.fields[0].name").value("spec_char1"))
                .andExpect(jsonPath("$.fields[0].value").value("EXE"))
                .andExpect(jsonPath("$.fields[0].status").value("AUTO_VALIDATED"))
                .andExpect(jsonPath("$.unclassifiedPairs[0].label").value("Echelle"))
                .andExpect(jsonPath("$.durationMs").value(1234));
    }

    @Test
    void unknown_json_properties_are_ignored_so_edoc_can_evolve_independently() throws Exception {
        when(service.extract(any(), any())).thenReturn(new ExtractionResponse(
                false, "SINGLE_PAGE", null, true, List.of(), List.of(), 10));

        mockMvc.perform(multipart("/api/v1/extractions")
                        .file(document())
                        .file(requestPart("""
                                {"projectCode":"p","champInconnu":42,
                                 "fields":[{"name":"f","labels":["Phase"],"typeFutur":"x"}]}""")))
                .andExpect(status().isOk());
    }

    @Test
    void an_unreadable_request_part_is_a_client_error_not_a_server_error() throws Exception {
        mockMvc.perform(multipart("/api/v1/extractions")
                        .file(document())
                        .file(requestPart("{ceci n'est pas du json")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(service, never()).extract(any(), any());
    }

    @Test
    void an_empty_document_is_rejected_before_any_ocr_call() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "vide.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/v1/extractions")
                        .file(empty)
                        .file(requestPart("{\"projectCode\":\"p\",\"fields\":[]}")))
                .andExpect(status().isBadRequest());

        verify(service, never()).extract(any(), any());
    }

    @Test
    void a_missing_request_part_is_reported_clearly() throws Exception {
        mockMvc.perform(multipart("/api/v1/extractions").file(document()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void an_ocr_outage_is_a_gateway_error_so_the_caller_can_offer_a_retry() throws Exception {
        // Une panne du service de lecture n'est pas une erreur de l'appelant : 502, pas 400 ni 500.
        when(service.extract(any(), any()))
                .thenThrow(new OcrException("Impossible de joindre l'API Mistral"));

        mockMvc.perform(multipart("/api/v1/extractions")
                        .file(document())
                        .file(requestPart("{\"projectCode\":\"p\",\"fields\":[]}")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }
}
