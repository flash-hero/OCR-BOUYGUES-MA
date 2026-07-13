package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client REST direct vers l'endpoint Mistral OCR ({@code POST /v1/ocr}).
 *
 * <p>On envoie toujours une <b>image d'une seule page</b> ({@code image_url}, PNG rendu à partir de
 * la première page du document) — jamais le PDF entier. Deux conséquences : (1) la limite de 30 pages
 * de l'API n'est jamais atteinte, quel que soit le nombre de pages du document source ; (2) le client
 * ne lit aucun fichier, il ne connaît que des octets d'image, ce qui garde la logique métier
 * indépendante de la provenance des documents (fichier d'exemple ou upload REST).</p>
 *
 * <p>Deux usages :</p>
 * <ul>
 *   <li>{@link #locateImage(byte[])} — passe 1 : localisation grossière du cartouche (voir
 *       {@link CartoucheLocationSchema}) ;</li>
 *   <li>{@link #analyzeImage(byte[])} — extraction <em>ouverte</em> (voir {@link CartoucheAnnotationSchema}) :
 *       lecture pleine page (format standard) ou découpage plein résolution d'un coin (passe 2).</li>
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
    private final OcrResponseCache cache;

    public MistralOcrClient(MistralOcrProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this(props, builder, mapper, OcrResponseCache.disabled());
    }

    public MistralOcrClient(MistralOcrProperties props, RestClient.Builder builder, ObjectMapper mapper,
                            OcrResponseCache cache) {
        this.props = props;
        this.mapper = mapper;
        this.cache = cache;
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

    /** Passe 1 : demande uniquement la zone approximative du cartouche sur l'image de la page. */
    public CartoucheLocation locateImage(byte[] pngBytes) {
        ObjectNode body = imageBody(pngBytes, CartoucheLocationSchema.format(mapper), CartoucheLocationSchema.PROMPT);
        return toLocation(post(body));
    }

    /** Extraction ouverte sur une image (page entière en format standard, ou découpage d'un coin). */
    public OcrResult analyzeImage(byte[] pngBytes) {
        ObjectNode body = imageBody(pngBytes, CartoucheAnnotationSchema.format(mapper), CartoucheAnnotationSchema.PROMPT);
        return toResult(post(body));
    }

    /** Corps JSON d'une requête image : modèle épinglé, schéma d'annotation, PNG en data-URI. */
    private ObjectNode imageBody(byte[] pngBytes, ObjectNode annotationFormat, String prompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.set("document_annotation_format", annotationFormat);
        body.put("document_annotation_prompt", prompt);
        body.put("include_image_base64", false);
        ObjectNode document = body.putObject("document");
        document.put("type", "image_url");
        document.put("image_url", PdfSupport.toImageDataUri(pngBytes));
        return body;
    }

    private JsonNode post(ObjectNode body) {
        byte[] jsonBody;
        try {
            jsonBody = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new MistralOcrException("Sérialisation de la requête impossible : " + e.getMessage(), e);
        }

        // Cache adressé par contenu : une requête déjà vue est resservie sans coût API.
        Optional<JsonNode> cached = cache.get(jsonBody);
        if (cached.isPresent()) {
            return cached.get();
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
        cache.put(jsonBody, response);
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

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String stripped = s.strip();
        return stripped.length() > MAX_ERROR_BODY ? stripped.substring(0, MAX_ERROR_BODY) + "…" : stripped;
    }
}
