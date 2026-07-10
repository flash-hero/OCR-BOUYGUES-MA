package com.bycn.edoc.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de l'appel Mistral OCR (préfixe {@code mistral.ocr} dans application.yml).
 *
 * <p>Le modèle est <b>épinglé</b> à {@code mistral-ocr-4-0} par défaut (jamais {@code -latest})
 * pour que les résultats restent reproductibles d'un run à l'autre.</p>
 *
 * @param apiKey             clé API (injectée depuis {@code MISTRAL_API_KEY}, jamais commitée)
 * @param baseUrl            racine de l'API : {@code https://api.mistral.ai} (La Plateforme) ou
 *                           l'endpoint Azure AI Foundry (Target URI)
 * @param ocrPath            chemin de l'endpoint OCR ({@code /v1/ocr} par défaut ; ajustable pour Azure)
 * @param model              identifiant de modèle épinglé (La Plateforme) ou nom de déploiement (Azure)
 * @param maxAnnotationPages nombre maximal de pages envoyées à l'annotation documentaire
 */
@ConfigurationProperties(prefix = "mistral.ocr")
public record MistralOcrProperties(
        String apiKey,
        @DefaultValue("https://api.mistral.ai") String baseUrl,
        @DefaultValue("/v1/ocr") String ocrPath,
        @DefaultValue("mistral-ocr-4-0") String model,
        @DefaultValue("8") int maxAnnotationPages
) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
