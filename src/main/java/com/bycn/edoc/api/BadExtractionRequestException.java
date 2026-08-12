package com.bycn.edoc.api;

/**
 * Demande d'extraction inexploitable (partie manquante, JSON illisible).
 *
 * <p>Distincte d'une panne du moteur : elle donne un {@code 400} et non un {@code 500}, pour que
 * l'appelant sache que le problème vient de sa requête et non du service.</p>
 */
public class BadExtractionRequestException extends RuntimeException {

    public BadExtractionRequestException(String message) {
        super(message);
    }
}
