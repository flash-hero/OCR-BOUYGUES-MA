package com.bycn.edoc.validation;

import com.bycn.edoc.classification.ClassifiedField;
import com.bycn.edoc.classification.FieldStatus;

/**
 * Un champ cible après validation de sa <i>valeur</i> contre la table de référence du projet.
 *
 * <p>Les cinq premiers composants sont repris tels quels de {@link ClassifiedField} : P4 ne
 * reclasse rien, il ne fait que confirmer (ou non) la valeur déjà associée par P3.</p>
 *
 * @param targetField           nom du champ demandé (ex. {@code LOT})
 * @param rawLabel              libellé lu, reporté de P3 — {@code null} si {@link FieldStatus#MISSING}
 * @param value                 valeur lue, reportée de P3 — {@code null} si {@link FieldStatus#MISSING}
 * @param classificationScore   score flou libellé↔synonyme de P3 ({@code ClassifiedField.matchScore}),
 *                              reporté tel quel — à ne pas confondre avec {@code referenceMatchScore}
 * @param matchedSynonym        synonyme retenu par P3, reporté tel quel
 * @param status                seul composant que P4 peut changer, et dans un seul sens :
 *                              {@link FieldStatus#TO_REVIEW} → {@link FieldStatus#AUTO_VALIDATED}.
 *                              Jamais l'inverse, jamais vers {@link FieldStatus#MISSING} — règle D11 :
 *                              une valeur non reconnue n'est pas rejetée, elle reste à vérifier.
 * @param matchedReferenceCode  code de la table confirmant la valeur, {@code null} si pas de table
 *                              pour ce champ ou si aucun code n'atteint le seuil
 * @param matchedReferenceLabel libellé de ce code, {@code null} dans les mêmes cas
 * @param referenceMatchScore   score flou valeur↔code (0-100), {@code null} dans les mêmes cas
 */
public record ValidatedField(
        String targetField,
        String rawLabel,
        String value,
        double classificationScore,
        String matchedSynonym,
        FieldStatus status,
        String matchedReferenceCode,
        String matchedReferenceLabel,
        Double referenceMatchScore
) {

    /**
     * Champ traversant P4 sans changement : aucune table pour ce champ, aucun code au-dessus du
     * seuil, ou champ {@link FieldStatus#MISSING} dès l'entrée. Le statut de P3 est conservé.
     */
    static ValidatedField carriedOver(ClassifiedField classified) {
        return new ValidatedField(
                classified.targetField(), classified.rawLabel(), classified.value(),
                classified.matchScore(), classified.matchedSynonym(), classified.status(),
                null, null, null);
    }

    /** Valeur confirmée par un code de la table : seul chemin qui pose {@link FieldStatus#AUTO_VALIDATED}. */
    static ValidatedField autoValidated(ClassifiedField classified, ReferenceEntry match, double referenceScore) {
        return new ValidatedField(
                classified.targetField(), classified.rawLabel(), classified.value(),
                classified.matchScore(), classified.matchedSynonym(), FieldStatus.AUTO_VALIDATED,
                match.code(), match.libelle(), referenceScore);
    }
}
