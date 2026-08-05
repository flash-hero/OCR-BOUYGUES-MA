package com.bycn.edoc.ocr;

/**
 * Erreur lisible lors d'un appel à l'API Mistral OCR (réseau injoignable, statut HTTP d'erreur,
 * réponse illisible…). Porte un message destiné à l'affichage, jamais une stack trace brute.
 */
public class OcrException extends RuntimeException {

    public OcrException(String message) {
        super(message);
    }

    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
