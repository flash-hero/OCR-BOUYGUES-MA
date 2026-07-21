package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p><b>Parallélisme (objectif : latence minimale).</b> Le budget d'appels n'est pas contraint mais
 * le temps l'est : tout ce qui peut être émis en même temps l'est. Les {@code samples} échantillons
 * d'un même appel partent <em>en parallèle</em> (confiance d'un vote à {@link OcrConsensus} au coût
 * horaire d'un seul appel), et {@link #analyzeImages(List)} traite plusieurs découpages en une seule
 * vague concurrente. On tolère les échecs partiels : tant qu'un échantillon aboutit, le consensus se
 * calcule.</p>
 */
public class MistralOcrClient implements AutoCloseable {

    private static final int MAX_ERROR_BODY = 500;
    private static final long BASE_BACKOFF_MS = 500;

    private final RestClient restClient;
    private final MistralOcrProperties props;
    private final ObjectMapper mapper;
    private final OcrResponseCache cache;
    /** Nombre d'échantillons par appel OCR (≥ 1). Voir {@link OcrConsensus}. */
    private final int samples;
    /** Nouvelles tentatives sur erreur transitoire (429/503/timeout), avec back-off exponentiel. */
    private final int maxRetries;
    /** Pool d'exécution des appels concurrents (échantillons + découpages). */
    private final Executor executor;
    /** Non nul seulement si ce client possède le pool (à fermer) ; nul si le pool est injecté (tests). */
    private final ExecutorService ownedExecutor;
    /** Compteur d'appels HTTP réellement émis (un défaut de cache = {@code samples} appels). */
    private final AtomicInteger networkCalls = new AtomicInteger();

    public MistralOcrClient(MistralOcrProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this(props, builder, mapper, OcrResponseCache.disabled());
    }

    public MistralOcrClient(MistralOcrProperties props, RestClient.Builder builder, ObjectMapper mapper,
                            OcrResponseCache cache) {
        this(props, builder, mapper, cache, newDaemonPool(), true);
    }

    /** Constructeur de test : pool injecté (ex. exécuteur synchrone), non fermé par ce client. */
    MistralOcrClient(MistralOcrProperties props, RestClient.Builder builder, ObjectMapper mapper,
                     OcrResponseCache cache, Executor executor) {
        this(props, builder, mapper, cache, executor, false);
    }

    private MistralOcrClient(MistralOcrProperties props, RestClient.Builder builder, ObjectMapper mapper,
                             OcrResponseCache cache, Executor executor, boolean ownsExecutor) {
        this.props = props;
        this.mapper = mapper;
        this.cache = cache;
        this.samples = Math.max(1, props.samples());
        this.maxRetries = Math.max(0, props.maxRetries());
        this.executor = executor;
        this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
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

    /** Pool à threads démons (la JVM peut sortir sans l'attendre), non borné : appels I/O concurrents. */
    private static ExecutorService newDaemonPool() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ocr-call");
            t.setDaemon(true);
            return t;
        });
    }

    /** Ferme le pool si ce client le possède (appelé par Spring à la fermeture du contexte). */
    @Override
    public void close() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    /** Passe 1 (synchrone) : zone approximative du cartouche. Voir {@link #locateImageAsync}. */
    public CartoucheLocation locateImage(byte[] pngBytes) {
        return joinUnwrapped(locateImageAsync(pngBytes));
    }

    /**
     * Passe 1 <b>asynchrone</b> : démarre immédiatement les échantillons de localisation et renvoie un
     * future. Permet à l'orchestrateur de lancer la localisation ET les découpages de coins dans la
     * <em>même vague</em> (une seule aller-retour réseau au lieu de deux passes empilées).
     */
    public CompletableFuture<CartoucheLocation> locateImageAsync(byte[] pngBytes) {
        ObjectNode body = imageBody(pngBytes, CartoucheLocationSchema.format(mapper), CartoucheLocationSchema.PROMPT);
        byte[] jsonBody = serialize(body);
        Optional<JsonNode> cached = cache.get(jsonBody);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(toLocation(cached.get()));
        }
        return allSuccesses(submitSamples(jsonBody, samples)).thenApply(responses -> {
            List<CartoucheLocation> locations = responses.stream().map(this::toLocation).toList();
            int chosen = OcrConsensus.pickLocationIndex(locations);
            cache.put(jsonBody, responses.get(chosen));
            return locations.get(chosen);
        });
    }

    /** Extraction ouverte sur une image (page entière en format standard, ou découpage d'un coin). */
    public OcrResult analyzeImage(byte[] pngBytes) {
        return analyzeImages(List.of(pngBytes)).get(0);
    }

    /** Extraction ouverte sur plusieurs images (synchrone). Voir {@link #analyzeImagesAsync}. */
    public List<OcrResult> analyzeImages(List<byte[]> pngs) {
        return joinUnwrapped(analyzeImagesAsync(pngs));
    }

    /**
     * Extraction ouverte sur <b>plusieurs</b> images en une seule vague concurrente : tous les
     * découpages (× {@code samples} échantillons chacun) partent en même temps. Le future se complète
     * avec un {@link OcrResult} par image, dans l'ordre d'entrée (chacun étant le consensus de ses
     * échantillons, figé dans le cache). Combiné à {@link #locateImageAsync}, tout tient en une vague.
     */
    public CompletableFuture<List<OcrResult>> analyzeImagesAsync(List<byte[]> pngs) {
        List<CompletableFuture<OcrResult>> perImage = new ArrayList<>(pngs.size());
        for (byte[] png : pngs) {
            ObjectNode body = imageBody(png, CartoucheAnnotationSchema.format(mapper),
                    CartoucheAnnotationSchema.PROMPT);
            byte[] jsonBody = serialize(body);
            Optional<JsonNode> cached = cache.get(jsonBody);
            if (cached.isPresent()) {
                perImage.add(CompletableFuture.completedFuture(toResult(cached.get())));
            } else {
                // submitSamples démarre les appels TOUT DE SUITE : toutes les images tournent en parallèle.
                perImage.add(allSuccesses(submitSamples(jsonBody, samples)).thenApply(r -> consensusOf(r, jsonBody)));
            }
        }
        return CompletableFuture
                .allOf(perImage.toArray(CompletableFuture[]::new))
                .thenApply(v -> perImage.stream().map(CompletableFuture::join).toList());
    }

    /** Nombre d'appels HTTP réellement émis vers Mistral depuis la création du client. */
    public int networkCalls() {
        return networkCalls.get();
    }

    /** Future des succès de {@code futures} (tolère les échecs partiels : n'échoue que si tous échouent). */
    private CompletableFuture<List<JsonNode>> allSuccesses(List<CompletableFuture<JsonNode>> futures) {
        return CompletableFuture
                .allOf(futures.toArray(CompletableFuture[]::new))
                .handle((v, t) -> collectSuccesses(futures));
    }

    /** Attend un future ; déballe {@link CompletionException} pour ne remonter que la vraie cause. */
    static <T> T joinUnwrapped(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            throw unwrap(e);
        }
    }

    /** Soumet {@code count} appels frais qui démarrent immédiatement sur le pool. */
    private List<CompletableFuture<JsonNode>> submitSamples(byte[] jsonBody, int count) {
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> postFresh(jsonBody), executor));
        }
        return futures;
    }

    /** Attend tous les futures ; renvoie les succès dans l'ordre. Échoue seulement si TOUS échouent. */
    private List<JsonNode> collectSuccesses(List<CompletableFuture<JsonNode>> futures) {
        List<JsonNode> out = new ArrayList<>(futures.size());
        RuntimeException lastError = null;
        for (CompletableFuture<JsonNode> future : futures) {
            try {
                out.add(future.join());
            } catch (CompletionException e) {
                lastError = unwrap(e);
            }
        }
        if (out.isEmpty()) {
            throw lastError != null ? lastError : new MistralOcrException("Aucun échantillon OCR n'a abouti.");
        }
        return out;
    }

    private static RuntimeException unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof MistralOcrException me) {
            return me;
        }
        return new MistralOcrException("Appel OCR concurrent en échec : " + (cause == null ? e : cause.getMessage()), e);
    }

    /** Vote de consensus sur des réponses brutes, puis gel du choix dans le cache. */
    private OcrResult consensusOf(List<JsonNode> responses, byte[] jsonBody) {
        List<OcrResult> results = new ArrayList<>(responses.size());
        List<CartoucheExtraction> extractions = new ArrayList<>(responses.size());
        for (JsonNode response : responses) {
            OcrResult result = toResult(response);
            results.add(result);
            // Une réponse sans annotation (ou illisible) compte comme un cartouche absent pour le vote.
            extractions.add(result.hasAnnotation() ? result.extraction() : new CartoucheExtraction(false, List.of()));
        }
        int chosen = OcrConsensus.pickExtractionIndex(extractions);
        cache.put(jsonBody, results.get(chosen).rawResponse());
        return results.get(chosen);
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

    private byte[] serialize(ObjectNode body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new MistralOcrException("Sérialisation de la requête impossible : " + e.getMessage(), e);
        }
    }

    /**
     * Un appel réseau frais, sans cache (le cache stocke le consensus, pas les échantillons bruts), avec
     * <b>nouvelles tentatives</b> sur erreur transitoire (429/503, timeout réseau) et back-off exponentiel.
     */
    private JsonNode postFresh(byte[] jsonBody) {
        int attempt = 0;
        while (true) {
            networkCalls.incrementAndGet();
            try {
                // Corps envoyé en byte[] : Content-Length connu et posé (la passerelle Azure APIM
                // refuse le Transfer-Encoding: chunked qu'implique un corps de longueur inconnue).
                JsonNode response = restClient.post()
                        .uri(props.ocrPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(jsonBody)
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    throw new MistralOcrException("Réponse vide de Mistral OCR.");
                }
                return response;
            } catch (RestClientResponseException e) {
                if (isRetryable(e.getStatusCode().value()) && attempt < maxRetries) {
                    backoff(attempt++);
                    continue;
                }
                throw new MistralOcrException(
                        "Mistral OCR a répondu " + e.getStatusCode() + " : " + truncate(e.getResponseBodyAsString()), e);
            } catch (ResourceAccessException e) {
                if (attempt < maxRetries) {
                    backoff(attempt++);
                    continue;
                }
                throw new MistralOcrException(
                        "Impossible de joindre l'API Mistral (" + props.baseUrl() + ") : " + e.getMessage(), e);
            }
        }
    }

    /** 429 (trop d'appels) et 503 (indisponible) sont transitoires : on réessaie. */
    private static boolean isRetryable(int status) {
        return status == 429 || status == 503;
    }

    /** Back-off exponentiel avec un peu de gigue, pour lisser une salve d'appels throttlée. */
    private void backoff(int attempt) {
        long delay = (long) (BASE_BACKOFF_MS * Math.pow(2, attempt)) + (long) (Math.random() * BASE_BACKOFF_MS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MistralOcrException("Attente avant nouvelle tentative interrompue.", ie);
        }
    }

    /**
     * Convertit une réponse en {@link OcrResult}, en <b>tolérant</b> une annotation illisible : un
     * échantillon dont le JSON est tronqué/malformé (ex. réponse coupée) est traité comme « pas
     * d'extraction » et le vote se fait sur les autres échantillons — jamais faire échouer le document
     * pour un seul échantillon défaillant (cf. crash observé sur 13.pdf).
     */
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
            // Échantillon défaillant : on l'ignore (pas d'extraction) plutôt que de tout faire échouer.
            return new OcrResult(response, null, null);
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
