package com.bycn.edoc.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.bycn.edoc.classification.ClassificationResult;
import com.bycn.edoc.classification.ClassifiedField;
import com.bycn.edoc.classification.FieldStatus;
import com.bycn.edoc.ocr.CartoucheField;
import java.util.ArrayList;
import java.util.List;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.junit.jupiter.api.Test;

/**
 * Tests adosses aux VRAIES tables de reference de mtbc_buche (comme les tests P3 sont adosses au
 * vrai schema_fields.yaml) : un cas qui passe ici passe sur le contenu reellement livre.
 * Aucun appel reseau.
 */
class FieldValidatorTest {

    private static final String PROJECT = "mtbc_buche";
    private static final double DEFAULT_THRESHOLD = 85;

    /** Registre qui note les champs pour lesquels une table a ete demandee. */
    private static class RecordingRegistry extends ReferenceTableRegistry {
        private final List<String> lookups = new ArrayList<>();

        @Override
        public List<ReferenceEntry> getTable(String projectCode, String targetField) {
            lookups.add(targetField);
            return super.getTable(projectCode, targetField);
        }
    }

    private static FieldValidator validator(ReferenceTableRegistry registry) {
        return new FieldValidator(registry, new ValidationProperties(DEFAULT_THRESHOLD));
    }

    private static FieldValidator validator() {
        return validator(new ReferenceTableRegistry());
    }

    /** Champ classe par P3 : une paire a ete associee, statut toujours TO_REVIEW. */
    private static ClassifiedField classified(String targetField, String label, String value) {
        return ClassifiedField.toReview(targetField, new CartoucheField(label, value), 100, label);
    }

    private static ClassificationResult input(ClassifiedField... fields) {
        return new ClassificationResult(List.of(fields), List.of());
    }

    private static ValidatedField fieldNamed(ValidationResult result, String targetField) {
        return result.fields().stream()
                .filter(f -> f.targetField().equals(targetField))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void value_matching_a_reference_code_exactly_is_auto_validated() {
        ValidationResult result = validator()
                .validate(input(classified("PHASE", "Phase", "EXE")), PROJECT);

        ValidatedField phase = fieldNamed(result, "PHASE");
        assertThat(phase.status()).isEqualTo(FieldStatus.AUTO_VALIDATED);
        assertThat(phase.matchedReferenceCode()).isEqualTo("EXE");
        assertThat(phase.matchedReferenceLabel()).isEqualTo("Exécution");
        assertThat(phase.referenceMatchScore()).isEqualTo(100.0);
    }

    @Test
    void classification_fields_are_carried_over_untouched_when_the_value_is_validated() {
        ValidationResult result = validator()
                .validate(input(classified("PHASE", "Pha", "EXE")), PROJECT);

        ValidatedField phase = fieldNamed(result, "PHASE");
        assertThat(phase.rawLabel()).isEqualTo("Pha");
        assertThat(phase.value()).isEqualTo("EXE");
        assertThat(phase.classificationScore()).isEqualTo(100);
        assertThat(phase.matchedSynonym()).isEqualTo("Pha");
    }

    @Test
    void value_absent_from_the_table_stays_to_review_and_is_never_rejected() {
        ValidationResult result = validator()
                .validate(input(classified("PHASE", "Phase", "999")), PROJECT);

        ValidatedField phase = fieldNamed(result, "PHASE");
        assertThat(phase.status()).isEqualTo(FieldStatus.TO_REVIEW);   // regle D11 : jamais un rejet
        assertThat(phase.value()).isEqualTo("999");                    // la valeur lue n'est jamais effacee
        assertThat(phase.matchedReferenceCode()).isNull();
        assertThat(phase.matchedReferenceLabel()).isNull();
        assertThat(phase.referenceMatchScore()).isNull();
    }

    @Test
    void missing_field_stays_missing_and_no_table_lookup_is_attempted() {
        RecordingRegistry registry = new RecordingRegistry();

        ValidationResult result = validator(registry)
                .validate(input(ClassifiedField.missing("PHASE")), PROJECT);

        ValidatedField phase = fieldNamed(result, "PHASE");
        assertThat(phase.status()).isEqualTo(FieldStatus.MISSING);
        assertThat(phase.value()).isNull();
        assertThat(phase.matchedReferenceCode()).isNull();
        assertThat(phase.referenceMatchScore()).isNull();
        assertThat(registry.lookups).isEmpty();   // aucune tentative de validation
    }

    @Test
    void field_without_any_csv_file_passes_through_unchanged() {
        // EMETTEUR n'a volontairement pas de table : c'est l'ABSENCE du fichier qui le fait
        // traverser P4, aucune liste de champs n'est ecrite en Java.
        ValidationResult result = validator()
                .validate(input(classified("EMETTEUR", "Emetteur", "CPI")), PROJECT);

        ValidatedField emetteur = fieldNamed(result, "EMETTEUR");
        assertThat(emetteur.status()).isEqualTo(FieldStatus.TO_REVIEW);   // pas de table != valide par defaut
        assertThat(emetteur.value()).isEqualTo("CPI");
        assertThat(emetteur.matchedReferenceCode()).isNull();
        assertThat(emetteur.matchedReferenceLabel()).isNull();
        assertThat(emetteur.referenceMatchScore()).isNull();
    }

    /**
     * Cas reel du corpus : 16.pdf lit « Lot : 003 » (voir docs/howtorun.md) face au code « 03 ».
     * Les deux premieres assertions figent la mesure brute qui a motive le retrait des zeros de
     * tete : sans lui, non seulement le bon code est sous le seuil, mais il est A EGALITE avec le
     * mauvais code (00, Generalites) — une auto-validation silencieusement fausse aurait ete
     * possible. Voir ValueNormalizer pour le raisonnement complet.
     */
    @Test
    void real_case_003_from_16pdf_validates_to_lot_03_thanks_to_leading_zero_stripping() {
        assertThat(FuzzySearch.ratio("003", "03")).isEqualTo(80);   // sous le seuil de 85
        assertThat(FuzzySearch.ratio("003", "00")).isEqualTo(80);   // ex aequo, sur le MAUVAIS code

        ValidationResult result = validator()
                .validate(input(classified("LOT", "Lot", "003")), PROJECT);

        ValidatedField lot = fieldNamed(result, "LOT");
        assertThat(lot.status()).isEqualTo(FieldStatus.AUTO_VALIDATED);
        assertThat(lot.matchedReferenceCode()).isEqualTo("03");
        assertThat(lot.matchedReferenceLabel()).isEqualTo("Terrassements");
        assertThat(lot.referenceMatchScore()).isEqualTo(100.0);
    }

    @Test
    void leading_zeros_are_not_stripped_from_alphanumeric_codes() {
        // O11 (TYPE) commence par la lettre O, pas un zero : le strip ne doit pas s'y appliquer.
        ValidationResult result = validator()
                .validate(input(classified("TYPE", "Type", "O11")), PROJECT);

        ValidatedField type = fieldNamed(result, "TYPE");
        assertThat(type.status()).isEqualTo(FieldStatus.AUTO_VALIDATED);
        assertThat(type.matchedReferenceCode()).isEqualTo("O11");
    }

    @Test
    void case_and_accents_do_not_prevent_a_match() {
        // Meme raison qu'en P3 : FuzzySearch.ratio est sensible a la casse.
        assertThat(FuzzySearch.ratio("rdc", "RDC")).isLessThan((int) DEFAULT_THRESHOLD);

        ValidationResult result = validator()
                .validate(input(classified("NIVEAU", "Niveau", "rdc")), PROJECT);

        ValidatedField niveau = fieldNamed(result, "NIVEAU");
        assertThat(niveau.status()).isEqualTo(FieldStatus.AUTO_VALIDATED);
        assertThat(niveau.matchedReferenceCode()).isEqualTo("RDC");
        assertThat(niveau.matchedReferenceLabel()).isEqualTo("Rez de Chaussée");
    }

    @Test
    void unclassified_pairs_cross_p4_without_any_modification() {
        List<CartoucheField> pairs = List.of(
                new CartoucheField("Affaire", "MTBC"),
                new CartoucheField("Maitre d'ouvrage", "BYCN"));
        ClassificationResult classification = new ClassificationResult(
                List.of(classified("PHASE", "Phase", "EXE")), pairs);

        ValidationResult result = validator().validate(classification, PROJECT);

        assertThat(result.unclassifiedPairs()).isEqualTo(pairs);
    }

    @Test
    void every_requested_field_is_returned_in_the_order_received_from_p3() {
        ValidationResult result = validator().validate(input(
                classified("PHASE", "Phase", "EXE"),
                ClassifiedField.missing("EMETTEUR"),
                classified("LOT", "Lot", "003")), PROJECT);

        assertThat(result.fields()).extracting(ValidatedField::targetField)
                .containsExactly("PHASE", "EMETTEUR", "LOT");
    }

    @Test
    void an_empty_value_is_never_auto_validated() {
        ValidationResult result = validator()
                .validate(input(classified("PHASE", "Phase", "")), PROJECT);

        assertThat(fieldNamed(result, "PHASE").status()).isEqualTo(FieldStatus.TO_REVIEW);
    }

    @Test
    void an_unknown_project_code_validates_nothing_and_rejects_nothing() {
        ValidationResult result = validator()
                .validate(input(classified("PHASE", "Phase", "EXE")), "projet_inexistant");

        ValidatedField phase = fieldNamed(result, "PHASE");
        assertThat(phase.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(phase.matchedReferenceCode()).isNull();
    }

    // --- Chemin generique : tables fournies par l'appelant (valeurs Documentum) ---------------

    @Test
    void a_caller_supplied_table_auto_validates_exactly_like_a_csv_one() {
        // Cas eDoc reel : les valeurs officielles viennent de Documentum, pas d'un CSV du classpath.
        ReferenceTableSource tables = field -> "spec_char1".equals(field)
                ? List.of(new ReferenceEntry("EXE", "Execution"), new ReferenceEntry("DOE", "Dossier"))
                : List.of();

        ValidationResult result = validator()
                .validate(input(classified("spec_char1", "Phase", "EXE")), tables);

        ValidatedField phase = fieldNamed(result, "spec_char1");
        assertThat(phase.status()).isEqualTo(FieldStatus.AUTO_VALIDATED);
        assertThat(phase.matchedReferenceCode()).isEqualTo("EXE");
        assertThat(phase.matchedReferenceLabel()).isEqualTo("Execution");
    }

    @Test
    void rule_d11_holds_on_caller_supplied_tables_too() {
        // Valeur absente de la table fournie : jamais un rejet, toujours TO_REVIEW.
        ReferenceTableSource tables = field -> List.of(new ReferenceEntry("EXE", "Execution"));

        ValidationResult result = validator()
                .validate(input(classified("spec_char1", "Phase", "ZZZ")), tables);

        ValidatedField phase = fieldNamed(result, "spec_char1");
        assertThat(phase.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(phase.value()).isEqualTo("ZZZ");
        assertThat(phase.matchedReferenceCode()).isNull();
    }

    @Test
    void a_field_without_any_caller_supplied_table_is_carried_over_untouched() {
        // Un champ libre (pas de refTable cote eDoc) : rien a valider, jamais "valide par defaut".
        ValidationResult result = validator()
                .validate(input(classified("spec_char2", "Texte", "n'importe quoi")), field -> List.of());

        ValidatedField libre = fieldNamed(result, "spec_char2");
        assertThat(libre.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(libre.matchedReferenceCode()).isNull();
    }

    @Test
    void a_null_table_from_the_caller_is_treated_as_no_table_not_a_crash() {
        // Robustesse : une source qui renvoie null ne doit pas faire echouer la validation.
        ValidationResult result = validator()
                .validate(input(classified("spec_char3", "Lot", "03")), field -> null);

        assertThat(fieldNamed(result, "spec_char3").status()).isEqualTo(FieldStatus.TO_REVIEW);
    }
}
