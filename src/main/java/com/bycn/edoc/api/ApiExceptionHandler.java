package com.bycn.edoc.api;

import com.bycn.edoc.ocr.OcrException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Traduit les échecs en réponses JSON lisibles, sans jamais renvoyer de trace d'exécution au client.
 *
 * <p>La distinction porte du sens pour l'appelant : un {@code 400} veut dire « corrige ta requête »,
 * un {@code 502} « le moteur de lecture n'a pas répondu, réessaie plus tard ». Côté eDoc, seul le
 * second justifie de proposer une nouvelle tentative à l'utilisateur.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadExtractionRequestException.class)
    public ResponseEntity<Map<String, String>> onBadRequest(BadExtractionRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> onMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Partie multipart manquante : " + e.getRequestPartName()));
    }

    /**
     * Échec de l'appel au service d'OCR (réseau, clé absente, statut d'erreur). C'est une panne de
     * dépendance, pas une erreur du client : {@code 502}.
     */
    @ExceptionHandler(OcrException.class)
    public ResponseEntity<Map<String, String>> onOcrFailure(OcrException e) {
        log.warn("Extraction impossible : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Le service de lecture OCR n'a pas abouti : " + e.getMessage()));
    }
}
