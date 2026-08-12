package com.bycn.edoc.api;

import com.bycn.edoc.classification.FieldStatus;
import com.bycn.edoc.validation.ValidatedField;

/**
 * Le résultat pour <b>un</b> champ demandé : ce qui a été lu, et à quel point on y croit.
 *
 * <p>Les trois statuts gardent exactement le sens défini en P3/P4 :</p>
 * <ul>
 *   <li>{@link FieldStatus#AUTO_VALIDATED} — valeur lue <em>et</em> confirmée par les valeurs
 *       officielles du champ : l'appelant peut la poser telle quelle ;</li>
 *   <li>{@link FieldStatus#TO_REVIEW} — valeur lue mais non confirmée (champ libre, ou valeur
 *       absente des valeurs officielles) : à faire vérifier par un humain, <b>jamais</b> rejetée
 *       silencieusement (règle D11) ;</li>
 *   <li>{@link FieldStatus#MISSING} — aucun libellé du cartouche ne correspond : champ laissé vide,
 *       jamais rempli au hasard.</li>
 * </ul>
 *
 * @param name             nom technique du champ, repris tel quel de la demande
 * @param value            valeur lue sur le document, {@code null} si {@link FieldStatus#MISSING}
 * @param status           voir ci-dessus
 * @param rawLabel         libellé réellement imprimé sur le document (traçabilité)
 * @param matchScore       score flou libellé lu ↔ libellé attendu (0-100)
 * @param referenceCode    code officiel confirmant la valeur, {@code null} si non confirmée
 * @param referenceLabel   intitulé de ce code, {@code null} dans les mêmes cas
 * @param referenceScore   score flou valeur lue ↔ code officiel, {@code null} dans les mêmes cas
 */
public record ExtractedField(
        String name,
        String value,
        FieldStatus status,
        String rawLabel,
        double matchScore,
        String referenceCode,
        String referenceLabel,
        Double referenceScore) {

    /** Projette un {@link ValidatedField} (sortie P4) sur le contrat public de l'API. */
    public static ExtractedField from(ValidatedField field) {
        return new ExtractedField(
                field.targetField(),
                field.value(),
                field.status(),
                field.rawLabel(),
                field.classificationScore(),
                field.matchedReferenceCode(),
                field.matchedReferenceLabel(),
                field.referenceMatchScore());
    }
}
