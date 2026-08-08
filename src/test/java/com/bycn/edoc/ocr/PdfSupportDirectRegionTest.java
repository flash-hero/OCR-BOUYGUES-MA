package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/**
 * Vérifie la conversion « fractions de l'image rendue » → « boîte de rognage de la page » de
 * {@link PdfSupport#renderRegionDirectPng} par une <b>pastille sombre</b>, pour les quatre
 * rotations {@code /Rotate} possibles. Le corpus contient des pages en 90, 180 et 270 : une erreur
 * de correspondance enverrait au modèle une zone quelconque du plan au lieu du cartouche, en
 * paraissant fonctionner sur les pages non tournées.
 */
class PdfSupportDirectRegionTest {

    /**
     * Page paysage 300×200 pt avec une pastille rouge dont le CENTRE, dans l'image <b>rendue</b>
     * (rotation appliquée), tombe toujours au quart haut-gauche — la position de la pastille dans
     * l'espace non tourné change donc avec la rotation, c'est tout l'objet du test.
     */
    private static byte[] pdfWithDotAtRenderedTopLeftQuarter(int rotate) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 200));
            page.setRotation(rotate);
            doc.addPage(page);
            // Centre visé dans l'image rendue : (25 % de la largeur, 25 % de la hauteur).
            // On remonte à la position non tournée pour y peindre la pastille.
            float[] center = switch (rotate) {
                case 90 -> new float[] {75, 50};    // rendu 200×300 : (50, 75) → non tourné (75, 50)
                case 180 -> new float[] {225, 50};  // rendu 300×200 : (75, 50) → non tourné (225, 50)
                case 270 -> new float[] {225, 150}; // rendu 200×300 : (50, 75) → non tourné (225, 150)
                default -> new float[] {75, 150};   // rendu 300×200 : (75, 50) → non tourné (75, 150)
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.addRect(center[0] - 10, center[1] - 10, 20, 20);
                cs.fill();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    /** Le rendu est en niveaux de gris : on cherche la pastille par sa noirceur, pas par sa couleur. */
    private static boolean containsDot(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFF) < 60) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void the_rendered_space_region_reaches_the_dot_for_every_page_rotation() throws Exception {
        // La région visée est le quart haut-gauche DE L'IMAGE RENDUE, quelle que soit la rotation.
        CropRegion topLeftQuarter = new CropRegion(0, 0, 0.5, 0.5);
        CropRegion oppositeQuarter = new CropRegion(0.5, 0.5, 0.5, 0.5);

        for (int rotate : new int[] {0, 90, 180, 270}) {
            byte[] pdf = pdfWithDotAtRenderedTopLeftQuarter(rotate);

            byte[] hit = PdfSupport.renderRegionDirectPng(pdf, 0, topLeftQuarter, 800, 7800);
            byte[] miss = PdfSupport.renderRegionDirectPng(pdf, 0, oppositeQuarter, 800, 7800);

            assertThat(containsDot(hit))
                    .as("rotation %d : la pastille doit être dans le quart haut-gauche rendu", rotate)
                    .isTrue();
            assertThat(containsDot(miss))
                    .as("rotation %d : le quart opposé ne doit pas la contenir", rotate)
                    .isFalse();
        }
    }
}
