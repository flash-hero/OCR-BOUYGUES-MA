package com.bycn.edoc.classification;

import com.bycn.edoc.ocr.CartoucheField;

/**
 * Un champ cible demandé par l'appel API, après tentative d'association à une paire du cartouche.
 *
 * @param targetField     nom du champ demandé (ex. {@code NUMERO}) — toujours renseigné
 * @param rawLabel        libellé lu tel qu'imprimé, {@code null} si {@link FieldStatus#MISSING}
 * @param value           valeur lue telle quelle, {@code null} si {@link FieldStatus#MISSING}
 * @param matchScore      score flou 0-100 du libellé retenu, {@code 0} si {@link FieldStatus#MISSING}
 * @param matchedSynonym  synonyme (forme d'origine) ayant produit le score, {@code null} si MISSING
 * @param status          jamais {@link FieldStatus#AUTO_VALIDATED} ici — réservé à P4
 */
public record ClassifiedField(
        String targetField,
        String rawLabel,
        String value,
        double matchScore,
        String matchedSynonym,
        FieldStatus status
) {

    /** Aucune paire lue ne dépasse le seuil pour ce champ (ou champ inconnu de la bibliothèque). */
    public static ClassifiedField missing(String targetField) {
        return new ClassifiedField(targetField, null, null, 0, null, FieldStatus.MISSING);
    }

    /**
     * Une paire a été associée au champ. Le statut est toujours {@link FieldStatus#TO_REVIEW} :
     * la classification ne valide pas la valeur, c'est le rôle de P4.
     */
    public static ClassifiedField toReview(String targetField, CartoucheField pair,
                                           double matchScore, String matchedSynonym) {
        return new ClassifiedField(targetField, pair.label(), pair.value(),
                matchScore, matchedSynonym, FieldStatus.TO_REVIEW);
    }
}
