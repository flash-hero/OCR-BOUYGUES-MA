package com.bycn.edoc.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de l'appel OCR (préfixe {@code ocr} dans application.yml).
 *
 * <p>Le modèle est toujours <b>épinglé</b> (jamais {@code -latest}) pour que les résultats restent
 * reproductibles d'un run à l'autre : {@code gpt-5.5} par défaut, nom du déploiement Azure AI
 * Foundry ; {@code mistral-ocr-4-0} si l'on repasse en {@link OcrApiFlavor#OCR}.</p>
 *
 * @param apiKey             clé API (injectée depuis {@code OCR_API_KEY}, jamais commitée)
 * @param baseUrl            racine de l'API : {@code https://api.mistral.ai} (La Plateforme) ou
 *                           l'endpoint Azure AI Foundry (Target URI)
 * @param ocrPath            chemin de l'endpoint OCR ({@code /v1/ocr} par défaut ; ajustable pour Azure).
 *                           Utilisé uniquement en {@link OcrApiFlavor#OCR}.
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
 * @param flavor             forme d'API visée (voir {@link OcrApiFlavor}) : {@code chat} par défaut
 *                           (modèle multimodal), {@code ocr} pour repasser sur Mistral Document AI.
 *                           Le reste du pipeline est identique dans les deux cas.
 * @param chatPath           chemin de l'endpoint chat, utilisé uniquement en {@link OcrApiFlavor#CHAT}
 * @param reasoningEffort    budget de réflexion du modèle de chat ({@code none} par défaut : la tâche
 *                           est un pur report de ce qui est imprimé, il n'y a rien à raisonner et la
 *                           réflexion ne coûte que de la latence). Vide = paramètre non envoyé, pour
 *                           un déploiement qui ne le supporterait pas.
 * @param maxOutputTokens    plafond de la réponse ({@code max_completion_tokens}). Doit rester large :
 *                           un cartouche dense peut dépasser 70 paires, et une réponse tronquée est un
 *                           échantillon perdu. 0 = paramètre non envoyé.
 * @param temperature        aléa d'échantillonnage. 0 = le plus déterministe, ce qu'on veut pour un
 *                           report littéral. Valeur négative = paramètre non envoyé. Contrairement à
 *                           {@code /v1/ocr}, l'endpoint chat l'accepte (vérifié sur le déploiement).
 * @param seed               graine de reproductibilité (best-effort, côté fournisseur). Valeur
 *                           négative = paramètre non envoyé.
 * @param imageDetail        niveau de détail de lecture de l'image ({@code high}, {@code low},
 *                           {@code auto}). {@code high} par défaut : le cartouche est fait de petits
 *                           caractères, une lecture dégradée les perd. Vide = paramètre non envoyé.
 * @param locateImageDetail  niveau de détail pour la <b>localisation</b> seulement. La passe 1 ne lit
 *                           aucun texte — elle situe une boîte — et son coût croît avec le détail :
 *                           mesuré sur les A0 denses, la même localisation en {@code low} rend la
 *                           même boîte plusieurs fois plus vite. Vide = paramètre non envoyé.
 */
@ConfigurationProperties(prefix = "ocr")
public record OcrProperties(
        String apiKey,
        @DefaultValue("https://api.mistral.ai") String baseUrl,
        @DefaultValue("/v1/ocr") String ocrPath,
        @DefaultValue("gpt-5.5") String model,
        @DefaultValue("8") int maxAnnotationPages,
        @DefaultValue("true") boolean cacheEnabled,
        @DefaultValue(".ocr-cache") String cacheDir,
        @DefaultValue("1") int samples,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("CHAT") OcrApiFlavor flavor,
        @DefaultValue("/openai/v1/chat/completions") String chatPath,
        @DefaultValue("none") String reasoningEffort,
        @DefaultValue("8000") int maxOutputTokens,
        @DefaultValue("0") double temperature,
        @DefaultValue("7") int seed,
        @DefaultValue("high") String imageDetail,
        @DefaultValue("low") String locateImageDetail
) {

    /** Chemin réellement appelé, selon la forme d'API configurée. */
    public String requestPath() {
        return flavor == OcrApiFlavor.CHAT ? chatPath : ocrPath;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
