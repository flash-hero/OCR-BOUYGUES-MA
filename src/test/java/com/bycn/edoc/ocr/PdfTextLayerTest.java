package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * La couche texte est la voie principale du moteur : elle doit sortir le texte du PDF <b>exact</b>
 * et <b>en préservant la mise en page</b> (sans quoi les colonnes d'un cartouche se mélangent et
 * l'appariement libellé/valeur devient impossible), et se déclarer inexploitable sur un document
 * sans texte, pour que l'appelant bascule sur l'image.
 */
class PdfTextLayerTest {

    /** Un PDF portant deux lignes : les en-têtes, puis les valeurs alignées dessous. */
    private static byte[] pdfWithCartoucheText() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(40, 700);
                // Un vrai plan porte aussi des annotations de dessin autour du cartouche : on en
                // met, à la fois pour être réaliste et pour dépasser le seuil d'exploitabilité.
                cs.showText("TN 174.00 EP 19 R.800 Grille FE= 171.75 BASSIN EP volume 42m3 "
                        + "decantation 50cm calcule sur pluie vicennale DRAIN 1 DRAIN 2 ARROSAGE");
                cs.newLineAtOffset(0, -18);
                cs.showText("PROJET PHASE EMETTEUR LOT ZONE NIVEAU TYPE NUMERO INDICE");
                cs.newLineAtOffset(0, -18);
                cs.showText("HUA EXE TRA 36 EXT EX PLA 3100 E");
                cs.endText();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    /** Une page vide : aucun texte, comme un plan scanné ou vectorisé en courbes. */
    private static byte[] pdfWithoutText() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void extracts_the_headers_and_their_values_keeping_the_two_line_layout() throws Exception {
        String text = PdfTextLayer.of(pdfWithCartoucheText(), 0);

        assertThat(text).contains("PROJET PHASE EMETTEUR LOT ZONE NIVEAU TYPE NUMERO INDICE");
        assertThat(text).contains("HUA EXE TRA 36 EXT EX PLA 3100 E");
        // La ligne d'en-têtes doit rester AVANT sa ligne de valeurs : c'est ce qui rend
        // l'appariement colonne par colonne possible.
        assertThat(text.indexOf("PROJET")).isLessThan(text.indexOf("HUA"));
    }

    @Test
    void a_document_without_a_text_layer_reports_nothing_so_the_caller_falls_back_to_the_image()
            throws Exception {
        assertThat(PdfTextLayer.of(pdfWithoutText(), 0)).isEmpty();
    }

    @Test
    void unreadable_bytes_report_nothing_rather_than_failing_the_document() {
        assertThat(PdfTextLayer.of(new byte[] {1, 2, 3}, 0)).isEmpty();
    }

    @Test
    void a_handful_of_stray_characters_is_not_considered_a_usable_text_layer() {
        // Quelques fragments d'annotation ne peuvent pas contenir un cartouche : s'en contenter
        // ferait manquer un document que l'image aurait lu.
        assertThat(PdfTextLayer.isUseful("172.50  TN 174.00")).isFalse();
        assertThat(PdfTextLayer.isUseful("x".repeat(PdfTextLayer.MIN_USEFUL_CHARS))).isTrue();
        assertThat(PdfTextLayer.isUseful(null)).isFalse();
    }
}
