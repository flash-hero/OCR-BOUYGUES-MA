package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client REST direct vers l'endpoint Mistral OCR ({@code POST /v1/ocr}).
 *
 * <p>Trois usages :</p>
 * <ul>
 *   <li>{@link #analyze(Path)} — lecture pleine page d'un PDF avec le schéma d'annotation
 *       <em>ouvert</em> (voir {@link CartoucheAnnotationSchema}) ;</li>
 *   <li>{@link #locate(Path)} — passe 1 : localisation grossière du cartouche (voir
 *       {@link CartoucheLocationSchema}) ;</li>
 *   <li>{@link #analyzeImage(byte[])} — passe 2 : extraction ouverte sur une <em>image</em>
 *       (le découpage plein résolution d'un coin de grand plan).</li>
 * </ul>
 *
 * <p>Le champ {@code document_annotation} renvoyé par l'API est une <em>chaîne</em> JSON, re-parsée
 * ici en {@link CartoucheExtraction} / {@link CartoucheLocation}.</p>
 */
public class MistralOcrClient {

    private static final int MAX_ERROR_BODY = 500;

    private final RestClient restClient;
    private final MistralOcrProperties props;
    private final ObjectMapper mapper;

    public MistralOcrClient(MistralOcrProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.restClient = builder
                .baseUrl(props.baseUrl())
                .defaultHeaders(headers -> {
                    if (props.hasApiKey()) {
                        // La Plateforme + serverless Azure : Authorization: Bearer.
                        headers.setBearerAuth(props.apiKey());
                        // Foundry (services.ai.azure.com) : en-tete api-key. Envoyer les deux est
                        // sans risque, le serveur utilise celui qu'il reconnait.
                        headers.add("api-key", props.apiKey());
                    }
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .build();
    }

    /** Envoie un PDF à Mistral OCR (lecture pleine page) et renvoie la réponse brute + l'annotation. */
    public OcrResult analyze(Path pdf) {
        PdfBytes pdf$ = readPdf(pdf);
        return toResult(post(buildRequest(pdf$.bytes(), pdf$.pageCount())));
    }

    /** Passe 1 : demande uniquement la zone approximative du cartouche sur la page. */
    public CartoucheLocation locate(Path pdf) {
        PdfBytes pdf$ = readPdf(pdf);
        ObjectNode body = baseBody(CartoucheLocationSchema.format(mapper), CartoucheLocationSchema.PROMPT);
        ObjectNode document = body.putObject("document");
        document.put("type", "document_url");
        document.put("document_url", PdfSupport.toBase64DataUri(pdf$.bytes()));
        addPages(body, pdf$.pageCount());
        return toLocation(post(body));
    }

    /** Passe 2 : extraction ouverte sur une image (le découpage plein résolution du cartouche). */
    public OcrResult analyzeImage(byte[] pngBytes) {
        ObjectNode body = baseBody(CartoucheAnnotationSchema.format(mapper), CartoucheAnnotationSchema.PROMPT);
        ObjectNode document = body.putObject("document");
        document.put("type", "image_url");
        document.put("image_url", PdfSupport.toImageDataUri(pngBytes));
        return toResult(post(body));
    }

    /** Construit le corps JSON de la requête pleine page (visible pour test). */
    ObjectNode buildRequest(byte[] pdfBytes, int pageCount) {
        ObjectNode body = baseBody(CartoucheAnnotationSchema.format(mapper), CartoucheAnnotationSchema.PROMPT);
        ObjectNode document = body.putObject("document");
        document.put("type", "document_url");
        document.put("document_url", PdfSupport.toBase64DataUri(pdfBytes));
        addPages(body, pageCount);
        return body;
    }

    private ObjectNode baseBody(ObjectNode annotationFormat, String prompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.set("document_annotation_format", annotationFormat);
        body.put("document_annotation_prompt", prompt);
        body.put("include_image_base64", false);
        return body;
    }

    private void addPages(ObjectNode body, int pageCount) {
        // L'annotation documentaire ne traite que les premières pages : on borne explicitement.
        int limit = Math.max(1, Math.min(pageCount, props.maxAnnotationPages()));
        ArrayNode pages = body.putArray("pages");
        for (int i = 0; i < limit; i++) {
            pages.add(i);
        }
    }

    private JsonNode post(ObjectNode body) {
        byte[] jsonBody;
        try {
            jsonBody = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new MistralOcrException("Sérialisation de la requête impossible : " + e.getMessage(), e);
        }

        JsonNode response;
        try {
            // Corps envoyé en byte[] : Content-Length connu et posé (la passerelle Azure APIM
            // refuse le Transfer-Encoding: chunked qu'implique un corps de longueur inconnue).
            response = restClient.post()
                    .uri(props.ocrPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MistralOcrException(
                    "Mistral OCR a répondu " + e.getStatusCode() + " : " + truncate(e.getResponseBodyAsString()), e);
        } catch (ResourceAccessException e) {
            throw new MistralOcrException(
                    "Impossible de joindre l'API Mistral (" + props.baseUrl() + ") : " + e.getMessage(), e);
        }

        if (response == null) {
            throw new MistralOcrException("Réponse vide de Mistral OCR.");
        }
        return response;
    }

    private OcrResult toResult(JsonNode response) {
        JsonNode annotationNode = response.get("document_annotation");
        if (annotationNode == null || !annotationNode.isTextual() || annotationNode.asText().isBlank()) {
            // Pas d'annotation : on renvoie quand même la réponse brute pour lecture humaine.
            return new OcrResult(response, null, null);
        }
        try {
            JsonNode parsedAnnotation = mapper.readTree(annotationNode.asText());
            CartoucheExtraction extraction = mapper.treeToValue(parsedAnnotation, CartoucheExtraction.class);
            return new OcrResult(response, parsedAnnotation, extraction);
        } catch (IOException e) {
            throw new MistralOcrException("Annotation JSON illisible dans la réponse : " + e.getMessage(), e);
        }
    }

    private CartoucheLocation toLocation(JsonNode response) {
        JsonNode annotationNode = response.get("document_annotation");
        if (annotationNode == null || !annotationNode.isTextual() || annotationNode.asText().isBlank()) {
            return new CartoucheLocation(false, "unknown", null);
        }
        try {
            JsonNode parsed = mapper.readTree(annotationNode.asText());
            boolean found = parsed.path("cartoucheFound").asBoolean(false);
            String corner = parsed.path("corner").asText("unknown");
            return new CartoucheLocation(found, corner, parsed);
        } catch (IOException e) {
            throw new MistralOcrException("Annotation de localisation illisible dans la réponse : " + e.getMessage(), e);
        }
    }

    private PdfBytes readPdf(Path pdf) {
        try {
            return new PdfBytes(PdfSupport.read(pdf), PdfSupport.pageCount(pdf));
        } catch (IOException e) {
            throw new MistralOcrException("Lecture du PDF impossible : " + pdf + " (" + e.getMessage() + ")", e);
        }
    }

    private record PdfBytes(byte[] bytes, int pageCount) {
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String stripped = s.strip();
        return stripped.length() > MAX_ERROR_BODY ? stripped.substring(0, MAX_ERROR_BODY) + "…" : stripped;
    }
}
