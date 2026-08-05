package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Corps de requête et lecture de réponse pour la forme {@link OcrApiFlavor#CHAT} : un modèle de chat
 * multimodal compatible OpenAI (déploiement {@code gpt-5.5} sur Azure AI Foundry).
 *
 * <p>Différences avec {@code /v1/ocr}, et rien d'autre :</p>
 * <ul>
 *   <li>l'image part comme <em>contenu de message</em> ({@code image_url} en data-URI) au lieu de
 *       {@code document} ;</li>
 *   <li>le schéma JSON part dans {@code response_format} au lieu de
 *       {@code document_annotation_format} — la forme de l'objet est la même, donc
 *       {@link CartoucheAnnotationSchema#format} et {@link CartoucheLocationSchema#format} sont
 *       réutilisés tels quels ;</li>
 *   <li>l'annotation revient dans {@code choices[0].message.content}, déjà en JSON (pas de
 *       double encodage comme {@code document_annotation}).</li>
 * </ul>
 *
 * <p><b>Latence.</b> Un modèle de raisonnement peut dépenser un budget de réflexion avant de
 * répondre — ici c'est du pur report de ce qui est imprimé, il n'y a rien à raisonner. On coupe donc
 * la réflexion ({@code reasoning_effort}, configurable) <em>et</em> on le redit dans la consigne de
 * rôle : les deux leviers agissent à des endroits différents (paramètre d'API vs comportement).</p>
 */
public final class ChatCartouchePayload {

    /**
     * Consigne de rôle : ne rendre que ce qui est <b>réellement imprimé</b>, et répondre tout de
     * suite. Elle est volontairement redondante avec {@code reasoning_effort} et avec le schéma
     * strict : un modèle de chat, contrairement à un moteur OCR, a tendance à commenter, à préfixer
     * son JSON d'une clôture de code, ou à compléter un cartouche « logiquement » — trois
     * comportements qui casseraient le contrat ouvert du pipeline (voir {@link CartoucheAnnotationSchema}).
     */
    static final String SYSTEM_PROMPT = """
            Tu es un moteur d'OCR de cartouche. Tu LIS l'image, tu ne réfléchis pas.
            RÉPONDS IMMÉDIATEMENT : aucune analyse, aucune vérification, aucune reformulation.
            Ta réponse entière est l'objet JSON du schéma demandé — rien avant, rien après :
            pas de phrase d'introduction, pas de clôture de code, pas d'explication, pas d'excuse.
            Tu ne renvoies QUE des libellés et des valeurs réellement présents et lisibles sur \
            l'image, recopiés caractère par caractère.
            Tu n'inventes rien, ne traduis rien, ne corriges rien, ne complètes rien : un champ que \
            tu ne vois pas n'existe pas.""";

    private ChatCartouchePayload() {
    }

    /** Modèle, schéma strict et paramètres communs à toutes les requêtes (image ou texte). */
    private static ObjectNode commonBody(ObjectMapper mapper, OcrProperties props, ObjectNode format) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.set("response_format", format);
        if (props.maxOutputTokens() > 0) {
            // max_completion_tokens (et non max_tokens) : c'est le nom attendu par les modèles de
            // raisonnement. Trop bas = JSON tronqué, donc échantillon perdu (toléré par le consensus).
            body.put("max_completion_tokens", props.maxOutputTokens());
        }
        if (props.reasoningEffort() != null && !props.reasoningEffort().isBlank()) {
            body.put("reasoning_effort", props.reasoningEffort());
        }
        // Déterminisme : contrairement à /v1/ocr, l'endpoint chat accepte temperature ET seed
        // (vérifié sur le déploiement). On lit un texte imprimé, il n'y a aucune raison de varier.
        // Le consensus multi-échantillons reste en place : la reproductibilité annoncée par le
        // fournisseur est « best-effort », jamais une garantie (voir OcrConsensus).
        if (props.temperature() >= 0) {
            body.put("temperature", props.temperature());
        }
        if (props.seed() >= 0) {
            body.put("seed", props.seed());
        }
        return body;
    }

    /**
     * Corps d'une requête {@code chat/completions} : modèle (nom du déploiement), schéma strict,
     * consigne de rôle, prompt de la passe, et l'image en data-URI.
     *
     * @param format le même objet {@code {type, json_schema}} que pour {@code /v1/ocr}
     * @param prompt le prompt de la passe (extraction ouverte ou localisation)
     */
    static ObjectNode body(ObjectMapper mapper, OcrProperties props, byte[] pngBytes,
                           ObjectNode format, String prompt) {
        return body(mapper, props, pngBytes, format, prompt, props.imageDetail());
    }

    /**
     * Corps d'une requête <b>texte</b> : même modèle, même schéma strict, mais le message utilisateur
     * porte le texte du document au lieu d'une image. Aucune transcription n'est demandée — le texte
     * est déjà exact (voir {@link PdfTextLayer}) — seulement le repérage du cartouche.
     */
    static ObjectNode textBody(ObjectMapper mapper, OcrProperties props, String documentText,
                               ObjectNode format, String prompt) {
        ObjectNode body = commonBody(mapper, props, format);
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", SYSTEM_PROMPT);
        messages.addObject()
                .put("role", "user")
                .put("content", prompt + "\n\n----- TEXTE DU DOCUMENT -----\n" + documentText);
        return body;
    }

    /**
     * Variante à niveau de détail explicite : la localisation (qui ne lit aucun texte) s'envoie en
     * détail réduit, la lecture reste en pleine résolution.
     */
    static ObjectNode body(ObjectMapper mapper, OcrProperties props, byte[] pngBytes,
                           ObjectNode format, String prompt, String imageDetail) {
        ObjectNode body = commonBody(mapper, props, format);
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", SYSTEM_PROMPT);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", prompt);
        ObjectNode image = content.addObject();
        image.put("type", "image_url");
        ObjectNode imageUrl = image.putObject("image_url");
        imageUrl.put("url", PdfSupport.toImageDataUri(pngBytes));
        if (imageDetail != null && !imageDetail.isBlank()) {
            // "high" pour la lecture : le modèle lit l'image en pleine résolution par tuiles, un
            // cartouche est fait de petits caractères. "low" pour la localisation : voir la propriété.
            imageUrl.put("detail", imageDetail);
        }
        return body;
    }

    /**
     * L'annotation JSON portée par une réponse de chat, ou {@code null} si la réponse n'a pas cette
     * forme (réponse OCR classique, réponse vide, message filtré…).
     */
    static String annotationText(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            return null;
        }
        return stripCodeFence(content.asText().strip());
    }

    /**
     * Retire une clôture de code ({@code ```json … ```}) éventuelle. La consigne l'interdit et le
     * schéma strict la rend improbable, mais un échantillon sur N peut la produire : mieux vaut le
     * récupérer que le perdre au parsing.
     */
    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        String withoutOpening = firstLineEnd < 0 ? "" : text.substring(firstLineEnd + 1);
        int closing = withoutOpening.lastIndexOf("```");
        return (closing < 0 ? withoutOpening : withoutOpening.substring(0, closing)).strip();
    }
}
