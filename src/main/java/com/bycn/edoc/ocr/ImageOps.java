package com.bycn.edoc.ocr;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Rotation d'un PNG par quarts de tour, en mémoire (le cœur ne manipule que des octets).
 *
 * <p><b>Pourquoi.</b> Sur certains plans, le texte du cartouche est couché (bande verticale le long
 * d'un bord). Mesuré sur 13.pdf : même découpage, seule l'orientation changeant, le modèle couché
 * lit une date fausse et perd le numéro ; redressé, il lit les deux correctement. Aucun détecteur
 * fiable n'existe côté PDF (la couche texte confond sa direction avec le {@code /Rotate} déjà
 * appliqué au rendu — mesuré et rejeté) : c'est donc la passe de localisation qui <em>voit</em>
 * l'orientation et prescrit la rotation corrective, appliquée ici avant la lecture.</p>
 */
final class ImageOps {

    private ImageOps() {
    }

    /**
     * Applique une rotation corrective (voir {@link CartoucheLocationSchema#ROTATIONS}) à un PNG.
     * Rotation inconnue ou {@code none} : octets rendus tels quels. Échec de décodage : idem —
     * mieux vaut lire une image couchée que ne rien lire du tout.
     */
    static byte[] rotate(byte[] pngBytes, String rotation) {
        int quarterTurnsCw = switch (rotation == null ? "none" : rotation.trim().toLowerCase()) {
            case "90-cw" -> 1;
            case "180" -> 2;
            case "90-ccw" -> 3;
            default -> 0;
        };
        if (quarterTurnsCw == 0) {
            return pngBytes;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (source == null) {
                return pngBytes;
            }
            BufferedImage rotated = rotateQuarters(source, quarterTurnsCw);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(rotated, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            return pngBytes;
        }
    }

    /** Rotation exacte par quarts de tour horaires (sans interpolation : aucun texte dégradé). */
    private static BufferedImage rotateQuarters(BufferedImage source, int quarterTurnsCw) {
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = quarterTurnsCw % 2 == 1;
        // Conserve le type d'origine : re-encoder un rendu gris en RGB triplerait le poids du PNG.
        int type = source.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : source.getType();
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, type);
        Graphics2D g = target.createGraphics();
        try {
            switch (quarterTurnsCw) {
                case 1 -> {
                    g.translate(h, 0);
                    g.rotate(Math.PI / 2);
                }
                case 2 -> {
                    g.translate(w, h);
                    g.rotate(Math.PI);
                }
                default -> {
                    g.translate(0, w);
                    g.rotate(-Math.PI / 2);
                }
            }
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }
}
