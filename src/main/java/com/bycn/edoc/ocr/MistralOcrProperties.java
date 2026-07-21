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
 * @param cacheEnabled       active le cache de réponses adressé par contenu (évite de re-facturer un
 *                           appel déjà effectué à l'identique ; {@code true} par défaut)
 * @param cacheDir           répertoire du cache de réponses (relatif au répertoire de travail)
 * @param samples            nombre d'échantillons par appel OCR (consensus). L'endpoint {@code /v1/ocr}
 *                           n'a NI {@code temperature} NI {@code seed} : deux requêtes identiques peuvent
 *                           différer. On échantillonne {@code samples} fois <em>en parallèle</em> et on
 *                           retient un représentant robuste (voir {@link OcrConsensus}). 5 par défaut
 *                           (mesuré : stabilise les annotations bimodales pour ~+5 s de queue de latence) ;
 *                           1 désactive le consensus. Le résultat est figé dans le cache.
 * @param maxRetries         nombre de nouvelles tentatives sur erreur transitoire (429/503, timeout réseau),
 *                           avec back-off exponentiel. 2 par défaut.
 */
@ConfigurationProperties(prefix = "mistral.ocr")
public record MistralOcrProperties(
        String apiKey,
        @DefaultValue("https://api.mistral.ai") String baseUrl,
        @DefaultValue("/v1/ocr") String ocrPath,
        @DefaultValue("mistral-ocr-4-0") String model,
        @DefaultValue("8") int maxAnnotationPages,
        @DefaultValue("true") boolean cacheEnabled,
        @DefaultValue(".ocr-cache") String cacheDir,
        @DefaultValue("5") int samples,
        @DefaultValue("2") int maxRetries
) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
