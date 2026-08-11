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
    void an_empty_header_of_the_same_name_never_beats_the_filled_pair_below_it() {
        // Cas 20.pdf : la zone lue contient l'en-tete vide du tableau de revisions (« DATE » sans
        // valeur, place plus haut) ET la vraie date du cartouche. Les deux libelles matchent aussi
        // bien ; c'est la valeur renseignee qui doit l'emporter, pas l'ordre de lecture.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false).classifyTargets(
                extraction(f("DATE", ""), f("DATE", "13/07/2012")),
                List.of(new TargetField("DATE", List.of("DATE"))));

        assertThat(fieldNamed(result, "DATE").value()).isEqualTo("13/07/2012");
    }

    @Test
    void a_field_whose_only_candidate_is_empty_still_returns_that_pair() {
        // Le departage ne doit jamais faire disparaitre une paire : sans concurrente renseignee,
        // l'en-tete vide reste ce qu'on a lu (charge a l'humain de trancher).
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false).classifyTargets(
                extraction(f("DATE", "")),
                List.of(new TargetField("DATE", List.of("DATE"))));

        ClassifiedField date = fieldNamed(result, "DATE");
        assertThat(date.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(date.value()).isEmpty();
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
        // « Affaire » est réellement lu sur de vrais plans : hors périmètre, jamais supprimé.
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

    // --- Chemin generique : synonymes fournis par l'appelant (projet eDoc) --------------------

    @Test
    void caller_supplied_labels_classify_a_field_absent_from_the_yaml() {
        // Cas eDoc reel : le champ s'appelle "spec_char1" (nom technique du projet, introuvable
        // dans schema_fields.yaml) et ses seuls libelles connus sont ceux configures dans eDoc.
        // Le chemin historique le classerait MISSING ; le chemin generique doit le remplir.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classifyTargets(extraction(f("Bâtiment", "BtA")),
                        List.of(new TargetField("spec_char1", List.of("Bâtiment", "Bat"))));

        ClassifiedField batiment = fieldNamed(result, "spec_char1");
        assertThat(batiment.status()).isEqualTo(FieldStatus.TO_REVIEW);
        assertThat(batiment.value()).isEqualTo("BtA");
        assertThat(batiment.rawLabel()).isEqualTo("Bâtiment");
    }

    @Test
    void caller_supplied_labels_stay_fuzzy_and_tolerate_case_and_accents() {
        // Meme garde-fou que pour le yaml : le cartouche imprime "BATIMENT", eDoc configure
        // "Bâtiment". Une egalite stricte renverrait un faux MISSING.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classifyTargets(extraction(f("BATIMENT", "BtA")),
                        List.of(new TargetField("spec_char1", List.of("Bâtiment"))));

        assertThat(fieldNamed(result, "spec_char1").status()).isEqualTo(FieldStatus.TO_REVIEW);
    }

    @Test
    void a_target_without_any_label_is_missing_never_an_error() {
        // eDoc peut fournir un champ sans libelle exploitable : cela doit degrader proprement.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classifyTargets(extraction(f("Phase", "EXE")),
                        List.of(new TargetField("spec_char9", List.of())));

        assertThat(fieldNamed(result, "spec_char9").status()).isEqualTo(FieldStatus.MISSING);
        assertThat(result.unclassifiedPairs()).containsExactly(f("Phase", "EXE"));
    }

    @Test
    void compound_printed_labels_containing_the_expected_word_still_match() {
        // Cartouches reels : le libelle attendu est imprime AVEC d'autres mots. La chaine entiere
        // s'effondre (ratio("numero de document","numero") = 50) alors que le mot est la, mot pour
        // mot : c'est la comparaison par ensembles de mots qui rattrape ces libelles composes.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classifyTargets(extraction(
                                f("NUMERO DE DOCUMENT", "PRORFR120"),
                                f("Zone / Niveau", "N+2"),
                                f("Titre du Dessin", "Plan RDC")),
                        List.of(new TargetField("numero", List.of("Numéro")),
                                new TargetField("niveau", List.of("Niveau")),
                                new TargetField("titre", List.of("Titre"))));

        assertThat(fieldNamed(result, "numero").value()).isEqualTo("PRORFR120");
        assertThat(fieldNamed(result, "niveau").value()).isEqualTo("N+2");
        assertThat(fieldNamed(result, "titre").value()).isEqualTo("Plan RDC");
    }

    @Test
    void word_set_comparison_never_bridges_unrelated_labels() {
        // Contre-epreuve : les couples qui ne doivent PAS se rattacher restent tres sous le seuil.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classifyTargets(extraction(f("Auteur", "J. Martin"), f("Date", "01/02/2026")),
                        List.of(new TargetField("numero", List.of("Numéro")),
                                new TargetField("type", List.of("Type"))));

        assertThat(fieldNamed(result, "numero").status()).isEqualTo(FieldStatus.MISSING);
        assertThat(fieldNamed(result, "type").status()).isEqualTo(FieldStatus.MISSING);
    }

    @Test
    void exact_label_still_outranks_a_word_subset_of_the_same_score() {
        // « Doc » doit aller au champ dont le synonyme est exactement « Doc », pas a celui dont
        // « N° Doc » ne matche que par sous-ensemble de mots : la correspondance par mots est
        // classee juste SOUS la chaine entiere, sinon l'ordre de declaration trancherait.
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classifyTargets(extraction(f("Doc", "X-1")),
                        List.of(new TargetField("champ_compose", List.of("N° Doc")),
                                new TargetField("champ_exact", List.of("Doc"))));

        assertThat(fieldNamed(result, "champ_exact").value()).isEqualTo("X-1");
        assertThat(fieldNamed(result, "champ_compose").status()).isEqualTo(FieldStatus.MISSING);
    }

    @Test
    void greedy_global_assignment_still_applies_on_caller_supplied_targets() {
        // Deux champs revendiquent la meme paire : le meilleur score l'emporte, l'autre ne prend
        // pas la paire par simple anteriorite dans la liste (regle deja verifiee sur le yaml).
        ClassificationResult result = classifier(DEFAULT_THRESHOLD, false)
                .classifyTargets(extraction(f("Niveau", "N+2")),
                        List.of(new TargetField("champ_a", List.of("Niveau")),
                                new TargetField("champ_b", List.of("Niveau"))));

        assertThat(fieldNamed(result, "champ_a").value()).isEqualTo("N+2");
        assertThat(fieldNamed(result, "champ_b").status()).isEqualTo(FieldStatus.MISSING);
    }
}
