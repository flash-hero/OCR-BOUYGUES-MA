package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Résultat d'un appel Mistral OCR, conservant à la fois la réponse brute (pour lecture humaine)
 * et l'annotation déjà désérialisée.
 *
 * @param rawResponse   la réponse HTTP complète de l'endpoint {@code /v1/ocr}
 * @param rawAnnotation le contenu de {@code document_annotation} re-parsé en arbre JSON (peut être {@code null})
 * @param extraction    l'annotation mappée sur {@link CartoucheExtraction} (peut être {@code null} si absente)
 */
public record OcrResult(JsonNode rawResponse, JsonNode rawAnnotation, CartoucheExtraction extraction) {

    public boolean hasAnnotation() {
        return extraction != null;
    }
}
