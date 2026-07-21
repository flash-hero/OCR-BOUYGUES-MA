package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Vérifie que le score distingue un cartouche d'identification (codes courts) d'un panneau
 * d'intervenants (adresses, téléphones, sociétés) — la cause du faux positif de 20.pdf.
 */
class CartoucheScoreTest {

    private static CartoucheExtraction ex(CartoucheField... fields) {
        return new CartoucheExtraction(true, List.of(fields));
    }

    private static CartoucheField f(String label, String value) {
        return new CartoucheField(label, value);
    }

    /** Le vrai cartouche : libellés d'identification + valeurs courtes → score franchement positif. */
    private static CartoucheExtraction identificationCartouche() {
        return ex(
                f("PHASE", "EXE"),
                f("ÉMETTEUR", "CPI"),
                f("LOT", "003"),
                f("INDICE", "G"),
                f("N° DOC", "0114"),
                f("ÉCHELLE", "1:100"));
    }

    /** Le panneau des intervenants de 20.pdf : rôles de sociétés, adresses, téléphones. */
    private static CartoucheExtraction intervenantsPanel() {
        return ex(
                f("CONSTRUCTEUR", "BOUYGUES BATIMENT IDF"),
                f("2, rue Transversale", "92635 Gennevilliers Cedex"),
                f("MAINTENEUR", "EXPRIMM"),
                f("MAITRISE D OEUVRE", "SETEC Batiment"),
                f("42/52 Quai de la Rapee", "Tel: 01 82 51 68 00"),
                f("COORDONNATEUR SPS", "SOCOTEC"));
    }

    @Test
    void empty_extraction_scores_zero() {
        assertThat(CartoucheScore.score(new CartoucheExtraction(false, List.of()))).isZero();
        assertThat(CartoucheScore.score(null)).isZero();
    }

    @Test
    void identification_cartouche_scores_positive() {
        assertThat(CartoucheScore.score(identificationCartouche())).isPositive();
    }

    @Test
    void intervenants_panel_scores_below_the_real_cartouche() {
        double cartouche = CartoucheScore.score(identificationCartouche());
        double intervenants = CartoucheScore.score(intervenantsPanel());

        // Le cœur du correctif 20.pdf : le panneau d'adresses doit se classer SOUS le vrai cartouche.
        assertThat(intervenants).isLessThan(cartouche);
    }

    @Test
    void phone_and_address_values_are_penalized() {
        double clean = CartoucheScore.score(ex(f("INDICE", "G")));
        double withPhone = CartoucheScore.score(ex(f("INDICE", "G"), f("Contact", "Tel: 01 82 51 68 00")));

        assertThat(withPhone).isLessThan(clean);
    }
}
