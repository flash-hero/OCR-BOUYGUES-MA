package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class PageInkBoundsTest {

    /** Page blanche avec un rectangle noir, en coordonnées pixel. */
    private static BufferedImage pageWithInk(int w, int h, int x, int y, int inkW, int inkH) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.fillRect(x, y, inkW, inkH);
        g.dispose();
        return image;
    }

    @Test
    void ink_in_the_upper_left_half_yields_a_box_covering_only_that_half() {
        // Cas 13.pdf : plan paysage posé dans la moitié haute d'une page portrait, bas totalement vide.
        BufferedImage page = pageWithInk(400, 800, 0, 0, 300, 400);

        CropRegion ink = PageInkBounds.probe(page);

        assertThat(ink.isFullPage()).isFalse();
        assertThat(ink.x()).isCloseTo(0.0, within(0.02));
        assertThat(ink.y()).isCloseTo(0.0, within(0.02));
        assertThat(ink.w()).isCloseTo(0.75, within(0.03));
        assertThat(ink.h()).isCloseTo(0.50, within(0.03));
    }

    @Test
    void a_page_already_covered_with_ink_is_left_untouched() {
        // Garde-fou : sur un document qui remplit sa feuille, la géométrie ne doit PAS bouger.
        BufferedImage page = pageWithInk(400, 400, 2, 2, 396, 396);

        assertThat(PageInkBounds.probe(page).isFullPage()).isTrue();
    }

    @Test
    void a_page_with_ordinary_margins_is_left_untouched_too() {
        // Cas 17.pdf (couverture ~0,72) : de simples marges. Rogner ne rapporte rien et déplace la
        // géométrie d'un document qui lisait bien — il y perdait la fin de deux valeurs.
        BufferedImage page = pageWithInk(400, 400, 20, 20, 350, 330);

        assertThat(PageInkBounds.probe(page).isFullPage()).isTrue();
    }

    @Test
    void a_blank_page_falls_back_to_the_full_page_rather_than_an_empty_box() {
        BufferedImage blank = pageWithInk(200, 200, 0, 0, 0, 0);

        assertThat(PageInkBounds.probe(blank).isFullPage()).isTrue();
    }

    @Test
    void isolated_dark_specks_do_not_count_as_ink() {
        // Un scan poussiéreux a des pixels sombres isolés : ils ne doivent pas faire croire que la
        // page est encrée de bord à bord, sinon le rognage ne servirait jamais sur un scan.
        BufferedImage page = pageWithInk(400, 400, 100, 100, 120, 120);
        page.setRGB(3, 3, 0);
        page.setRGB(396, 396, 0);

        CropRegion ink = PageInkBounds.probe(page);

        assertThat(ink.x()).isCloseTo(0.24, within(0.03));
        assertThat(ink.w()).isCloseTo(0.32, within(0.04));
    }

    @Test
    void a_corner_taken_within_an_ink_box_stays_inside_that_box() {
        // C'est l'opération qui corrige 13.pdf : « bas-gauche » doit désigner le bas-gauche du
        // DESSIN, pas celui de la feuille.
        CropRegion ink = new CropRegion(0, 0, 0.775, 0.540);

        CropRegion bottomLeft = CropRegion.forCorner("bottom-left", 0.40,
                TwoPassCartoucheExtractor.BAND_FRACTION).within(ink);

        assertThat(bottomLeft.x()).isCloseTo(0.0, within(0.001));
        assertThat(bottomLeft.y()).isCloseTo(0.324, within(0.001));
        assertThat(bottomLeft.w()).isCloseTo(0.310, within(0.001));
        assertThat(bottomLeft.h()).isCloseTo(0.216, within(0.001));
        // Entièrement contenu dans la boîte d'encre.
        assertThat(bottomLeft.y() + bottomLeft.h()).isLessThanOrEqualTo(ink.y() + ink.h() + 1e-9);
        assertThat(bottomLeft.x() + bottomLeft.w()).isLessThanOrEqualTo(ink.x() + ink.w() + 1e-9);
    }

    @Test
    void a_full_page_ink_box_leaves_the_corner_exactly_as_before() {
        // Non-régression : sur les documents dont le dessin remplit la feuille, rien ne change.
        CropRegion plain = CropRegion.forCorner("bottom-right", 0.40, TwoPassCartoucheExtractor.BAND_FRACTION);

        assertThat(plain.within(PdfSupport.FULL_PAGE)).isEqualTo(plain);
        assertThat(plain.within(null)).isEqualTo(plain);
    }
}
