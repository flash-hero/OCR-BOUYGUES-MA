package com.bycn.edoc.validation;

import com.bycn.edoc.classification.ClassificationResult;
import com.bycn.edoc.classification.ClassifiedField;
import com.bycn.edoc.classification.FieldStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.xdrop.fuzzywuzzy.FuzzySearch;

/**
 * P4 — confirme la <i>valeur</i> d'un champ classé contre la table de référence de son projet.
 *
 * <p>À ne pas confondre avec P3 : la classification répond « ce libellé désigne ce champ », la
 * validation répond « cette valeur est un code connu de ce projet ». Les deux comparaisons sont
 * floues, mais elles ne portent ni sur les mêmes chaînes ni sur le même seuil.</p>
 *
 * <p><b>Règle D11 — aucune table n'est un vocabulaire fermé.</b> Une valeur absente de la table ne
 * fait jamais échouer le champ et n'est jamais effacée : elle reste {@link FieldStatus#TO_REVIEW}
 * pour vérification humaine. P4 ne peut faire qu'une seule chose au statut, dans un seul sens :
 * {@code TO_REVIEW → AUTO_VALIDATED}.</p>
 */
public class FieldValidator {

    private final ReferenceTableRegistry registry;
    private final ValidationProperties properties;

    public FieldValidator(ReferenceTableRegistry registry, ValidationProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * @param projectCode projet eDoc fourni par l'appel API : détermine quelles tables s'appliquent.
     *                    Les tables sont lues dans {@link ReferenceTableRegistry} (CSV du classpath).
     */
    public ValidationResult validate(ClassificationResult classification, String projectCode) {
        return validate(classification, targetField -> registry.getTable(projectCode, targetField));
    }

    /**
     * Variante où l'appelant fournit lui-même les tables (voir {@link ReferenceTableSource}) :
     * indispensable pour un projet eDoc, dont les valeurs officielles viennent de Documentum et
     * non des CSV embarqués. La logique de validation est strictement la même dans les deux cas,
     * règle D11 comprise.
     */
    public ValidationResult validate(ClassificationResult classification, ReferenceTableSource tables) {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(tables, "tables");
        List<ValidatedField> validated = new ArrayList<>(classification.fields().size());
        for (ClassifiedField field : classification.fields()) {
            validated.add(validateOne(field, tables));
        }
        // unclassifiedPairs traverse P4 sans lecture ni modification : ce ne sont pas des champs.
        return new ValidationResult(validated, classification.unclassifiedPairs());
    }

    private ValidatedField validateOne(ClassifiedField field, ReferenceTableSource tables) {
        if (field.status() == FieldStatus.MISSING) {
            return ValidatedField.carriedOver(field);   // aucune valeur lue : rien à valider
        }
        List<ReferenceEntry> table = tables.tableFor(field.targetField());
        if (table == null || table.isEmpty()) {
            return ValidatedField.carriedOver(field);   // pas de table = rien à valider, jamais « valide par défaut »
        }
        Match best = bestMatch(field.value(), table);
        if (best == null || best.score() < properties.fuzzyThreshold()) {
            return ValidatedField.carriedOver(field);   // D11 : non reconnu ≠ rejeté
        }
        return ValidatedField.autoValidated(field, best.entry(), best.score());
    }

    /**
     * Meilleur code de la table face à la valeur lue, les deux normalisés
     * ({@link ValueNormalizer} — voir la mesure du cas « 003 » vs « 03 » qui y est documentée).
     *
     * <p>À égalité de score, le premier code dans l'ordre du CSV l'emporte : le résultat reste
     * déterministe d'un run à l'autre. Mesuré sur les 5 tables de {@code mtbc_buche} : aucune paire
     * de codes distincts d'une même table n'atteint 85 l'une contre l'autre, donc au seuil retenu
     * cette égalité n'est pas atteignable — la départager n'arbitre aucun cas réel.</p>
     *
     * @return {@code null} si la valeur est vide ou si la table n'offre aucun code comparable
     */
    private static Match bestMatch(String value, List<ReferenceEntry> table) {
        String normalizedValue = ValueNormalizer.normalize(value);
        if (normalizedValue.isEmpty()) {
            return null;
        }
        Match best = null;
        for (ReferenceEntry entry : table) {
            String normalizedCode = ValueNormalizer.normalize(entry.code());
            if (normalizedCode.isEmpty()) {
                continue;
            }
            double score = FuzzySearch.ratio(normalizedValue, normalizedCode);
            if (best == null || score > best.score()) {
                best = new Match(entry, score);
            }
        }
        return best;
    }

    private record Match(ReferenceEntry entry, double score) {
    }
}
