package com.bycn.edoc.ocr;

/**
 * Forme d'API visée par {@link OcrClient}.
 *
 * <p>Le pipeline (découpage des coins, consensus, score, cache, classification, validation) est
 * <b>identique</b> dans les deux cas : seuls le corps de requête et l'endroit où lire l'annotation
 * dans la réponse changent. Le choix se fait par configuration ({@code ocr.flavor}), jamais
 * en dur dans le code métier.</p>
 */
public enum OcrApiFlavor {

    /**
     * Mistral Document AI : {@code POST /v1/ocr}, schéma dans {@code document_annotation_format},
     * réponse dans le champ texte {@code document_annotation}.
     */
    OCR,

    /**
     * Modèle de chat multimodal compatible OpenAI (déploiement {@code gpt-5.5} sur Azure AI Foundry) :
     * {@code POST /openai/v1/chat/completions}, schéma dans {@code response_format}, réponse dans
     * {@code choices[0].message.content}.
     */
    CHAT
}
