package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CartouchePlausibilityTest {

    private static CartoucheField f(String label, String value) {
        return new CartoucheField(label, value);
    }

    @Test
    void title_only_result_is_rejected() {
        // Le cas d'échec observé : 1 seule paire, une longue phrase (le titre du plan).
        CartoucheExtraction ex = new CartoucheExtraction(true,
                List.of(f("Titre", "Tableau des grues de la Corne Ouest - Tranche 1")));
        assertThat(CartouchePlausibility.looksLikeCartouche(ex)).isFalse();
    }

    @Test
    void rich_form_of_short_codes_is_accepted() {
        // Le vrai cartouche : plusieurs paires courtes libellé/valeur.
        CartoucheExtraction ex = new CartoucheExtraction(true, List.of(
                f("PROJET", "54B"),
                f("EMETTEUR", "LACH"),
                f("LOT", "MIN"),
                f("PHASE", "EXE"),
                f("INDICE", "A")));
        assertThat(CartouchePlausibility.looksLikeCartouche(ex)).isTrue();
    }

    @Test
    void a_few_long_values_are_tolerated_when_enough_short_codes_exist() {
        // Un vrai cartouche peut contenir un nom de projet / une adresse longue : toléré.
        CartoucheExtraction ex = new CartoucheExtraction(true, List.of(
                f("PROJET", "FUTUR PALAIS DE JUSTICE DE PARIS"),
                f("INDICE", "C"),
                f("PHASE", "PRO"),
                f("LOT", "03")));
        assertThat(CartouchePlausibility.looksLikeCartouche(ex)).isTrue();
    }

    @Test
    void not_found_or_too_few_fields_is_rejected() {
        assertThat(CartouchePlausibility.looksLikeCartouche(
                new CartoucheExtraction(false, List.of()))).isFalse();
        assertThat(CartouchePlausibility.looksLikeCartouche(
                new CartoucheExtraction(true, List.of(f("A", "1"), f("B", "2"))))).isFalse();
    }
}
