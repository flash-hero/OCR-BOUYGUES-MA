package com.bycn.edoc.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bycn.edoc.ocr.CartoucheExtraction;
import com.bycn.edoc.ocr.CartoucheField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldClassifierTest {

    private static final double DEFAULT_THRESHOLD = 80;

    private static CartoucheField f(String label, String value) {
        return new CartoucheField(label, value);
    }

    private static CartoucheExtraction extraction(CartoucheField... pairs) {
        return new CartoucheExtraction(true, List.of(pairs));
    }

    /** Classifieur adosse a la vraie bibliotheque partagee schema_fields.yaml. */
    private static FieldClassifier classifier(double threshold, boolean useHypothesis) {
        return new FieldClassifier(
                SchemaFieldsRegistry.fromClasspath(SchemaFieldsRegistry.DEFAULT_RESOURCE),
                new ClassificationProperties(threshold, useHypothesis));
    }

    private static ClassifiedField fieldNamed(ClassificationResult result, String targetField) {
        return result.fields().stream()
                .filter(c -> c.targetField().equals(targetField))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void confirmed_synonym_matches_its_target_field() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("N° GED", "GED-001")), List.of("NUMERO"));

        ClassifiedField numero = fieldNamed(result, "NUMERO");
        assertThat(numero.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(numero.rawLabel()).isEqualTo("N° GED");
        assertThat(numero.value()).isEqualTo("GED-001");
        assertThat(numero.matchedSynonym()).isEqualTo("N° GED");
        assertThat(numero.matchScore()).isEqualTo(100);
        assertThat(result.unclassifiedPairs()).isEmpty();
    }

    @Test
    void uppercase_and_accent_variants_of_the_corpus_still_match() {
        // Garde-fou de non-regression : FuzzySearch.ratio est sensible a la casse ET aux accents
        // (mesure sur la bibliotheque : ratio("NUM","Num")=33, ratio("LEVEL","Level")=20,
        // ratio("EMETTEUR","Émetteur")=0). Sans normalisation prealable des deux cotes, ces
        // variantes pourtant documentees dans CLAUDE.md tomberaient en MISSING.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("NUM", "PL-042"), f("LEVEL", "R+2"), f("EMETTEUR", "LACH")),
                        List.of("NUMERO", "NIVEAU", "EMETTEUR"));

        assertThat(fieldNamed(result, "NUMERO").value()).isEqualTo("PL-042");
        assertThat(fieldNamed(result, "NIVEAU").value()).isEqualTo("R+2");
        assertThat(fieldNamed(result, "EMETTEUR").value()).isEqualTo("LACH");
        assertThat(result.fields()).allSatisfy(c ->
                assertThat(c.status()).isEqualTo(FieldStatus.TO_REVIEW));
    }

    @Test
    void minor_typo_still_matches_because_comparison_is_fuzzy_not_strict() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Emeteur", "LACH")), List.of("EMETTEUR"));

        ClassifiedField emetteur = fieldNamed(result, "EMETTEUR");
        assertThat(emetteur.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(emetteur.value()).isEqualTo("LACH");
        // Ni egalite stricte (sinon MISSING), ni score parfait : c'est bien du flou.
        assertThat(emetteur.matchScore()).isGreaterThanOrEqualTo(DEFAULT_THRESHOLD).isLessThan(100);
    }

    @Test
    void hypothesis_synonym_is_ignored_when_the_flag_is_off() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Spécialité", "GC")), List.of("LOT"));

        assertThat(fieldNamed(result, "LOT").status()).isEqualTo(FieldStatus.MISSING);
        // La paire n'est pas perdue pour autant.
        assertThat(result.unclassifiedPairs()).containsExactly(f("Spécialité", "GC"));
    }

    @Test
    void hypothesis_synonym_is_used_when_the_flag_is_on() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, true)
                .classify(extraction(f("Spécialité", "GC")), List.of("LOT"));

        ClassifiedField lot = fieldNamed(result, "LOT");
        assertThat(lot.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(lot.value()).isEqualTo("GC");
        assertThat(lot.matchedSynonym()).isEqualTo("Spécialité");
        assertThat(result.unclassifiedPairs()).isEmpty();
    }

    @Test
    void no_pair_above_the_threshold_yields_missing_with_null_label_and_zero_score() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Affaire", "54B")), List.of("ZONE"));

        ClassifiedField zone = fieldNamed(result, "ZONE");
        assertThat(zone.status()).isEqualTo(FieldStatus.MISSING);
        assertThat(zone.rawLabel()).isNull();
        assertThat(zone.value()).isNull();
        assertThat(zone.matchedSynonym()).isNull();
        assertThat(zone.matchScore()).isZero();
    }

    @Test
    void required_field_absent_from_the_library_is_missing() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Phase", "EXE")), List.of("CHAMP_INEXISTANT"));

        assertThat(fieldNamed(result, "CHAMP_INEXISTANT").status()).isEqualTo(FieldStatus.MISSING);
        assertThat(result.unclassifiedPairs()).containsExactly(f("Phase", "EXE"));
    }

    @Test
    void pair_matching_no_target_field_stays_in_unclassified_pairs() {
        // « Affaire » est réellement lu sur 16.pdf (smoke test) : hors périmètre, jamais supprimé.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Affaire", "54B"), f("Phase", "EXE")), List.of("PHASE"));

        assertThat(fieldNamed(result, "PHASE").value()).isEqualTo("EXE");
        assertThat(result.unclassifiedPairs()).containsExactly(f("Affaire", "54B"));
    }

    @Test
    void conflict_is_resolved_globally_not_by_iteration_order() {
        // Bibliotheque synthetique : elle permet de fabriquer des scores exactement etages, ce que
        // le vocabulaire reel (bien separe) ne permet pas. Scores mesures :
        //   BETA/"code"  = 100   ALPHA/"code" = 89   BETA/"cod" = 86   ALPHA/"cod" = 75
        // Glouton GLOBAL : BETA prend "code" (100), ALPHA retombe sur "cod" (75)  => total 175.
        // Premier-match-gagne (ALPHA itere en premier) : ALPHA prendrait "code" (89) et BETA
        // se rabattrait sur "cod" (86) => total 175 mais mauvaise affectation. C'est cette
        // difference que le test verrouille.
        SchemaFieldsRegistry registry = new SchemaFieldsRegistry(Map.of(
                "ALPHA", new SynonymEntry(List.of("coden"), List.of()),
                "BETA", new SynonymEntry(List.of("code"), List.of())));
        FieldClassifier classifier = new FieldClassifier(registry, new ClassificationProperties(70, false));

        ClassificationResult result = classifier.classify(
                extraction(f("code", "X"), f("cod", "Y")),
                List.of("ALPHA", "BETA"));

        assertThat(fieldNamed(result, "BETA").rawLabel()).isEqualTo("code");
        assertThat(fieldNamed(result, "ALPHA").rawLabel()).isEqualTo("cod");
        assertThat(result.unclassifiedPairs()).isEmpty();
    }

    @Test
    void conflict_loser_without_a_second_candidate_falls_back_to_missing() {
        // Collision reelle annoncee dans schema_fields.yaml : "Doc" (hypothesis de TYPE) contre
        // "N° Doc" (confirmed de NUMERO). Seuil abaisse a 60 pour que NUMERO soit un vrai
        // concurrent sur cette paire (ratio("doc","n°doc") = 75).
        ClassificationResult result = classifier(60, true)
                .classify(extraction(f("Doc", "PLAN")), List.of("NUMERO", "TYPE"));

        ClassifiedField type = fieldNamed(result, "TYPE");
        assertThat(type.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(type.matchedSynonym()).isEqualTo("Doc");
        assertThat(type.matchScore()).isEqualTo(100);
        // NUMERO revendiquait la meme paire mais moins fort, et n'a pas de repli.
        assertThat(fieldNamed(result, "NUMERO").status()).isEqualTo(FieldStatus.MISSING);
        assertThat(result.unclassifiedPairs()).isEmpty();
    }

    @Test
    void one_pair_is_never_assigned_to_two_target_fields() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Indice", "B")), List.of("Indice", "Indice"));

        assertThat(result.fields()).hasSize(2);
        assertThat(result.fields().get(0).status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(result.fields().get(1).status()).isEqualTo(FieldStatus.MISSING);
    }

    @Test
    void classified_fields_follow_the_required_fields_order() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Phase", "EXE"), f("Lot", "GC")),
                        List.of("LOT", "ZONE", "PHASE"));

        assertThat(result.fields()).extracting(ClassifiedField::targetField)
                .containsExactly("LOT", "ZONE", "PHASE");
        assertThat(fieldNamed(result, "ZONE").status()).isEqualTo(FieldStatus.MISSING);
    }

    @Test
    void empty_extraction_marks_every_required_field_missing() {
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(new CartoucheExtraction(false, List.of()), List.of("PHASE", "NUMERO"));

        assertThat(result.fields()).hasSize(2)
                .allSatisfy(c -> assertThat(c.status()).isEqualTo(FieldStatus.MISSING));
        assertThat(result.unclassifiedPairs()).isEmpty();
    }

    @Test
    void field_with_no_declared_synonym_can_never_match() {
        // Titre2/Titre3 n'ont aucun synonyme confirme : ils restent MISSING par construction.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Titre2", "Coupe AA")), List.of("Titre2"));

        assertThat(fieldNamed(result, "Titre2").status()).isEqualTo(FieldStatus.MISSING);
        assertThat(result.unclassifiedPairs()).containsExactly(f("Titre2", "Coupe AA"));
    }

    @Test
    void classification_never_auto_validates() {
        // AUTO_VALIDATED est reserve a P4 (validation contre les tables de reference).
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classify(extraction(f("Phase", "EXE"), f("N° Doc", "PL-042")),
                        List.of("PHASE", "NUMERO"));

        assertThat(result.fields()).noneMatch(c -> c.status() == FieldStatus.AUTO_VALIDATED);
    }
}
