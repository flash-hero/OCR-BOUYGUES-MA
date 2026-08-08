package com.bycn.edoc.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Boîte englobante de l'<b>encre</b> de la page : la zone réellement dessinée, marges blanches
 * exclues.
 *
 * <p><b>Pourquoi.</b> Le découpage par coins suppose que le dessin occupe la feuille. C'est faux sur
 * une partie du corpus : {@code 13.pdf} est un plan paysage posé dans la moitié haute d'une page
 * portrait, les 45&nbsp;% du bas étant totalement vides. Ses coins « bas-gauche » et « bas-droite »
 * ne contenaient donc <em>que du blanc</em> (24 Ko de PNG contre 576 Ko pour le haut-gauche), et son
 * cartouche — une bande à mi-hauteur — n'était atteint par aucun des quatre coins. Résultat : 7 paires
 * et le contrôle qualité en échec, alors que le cartouche est parfaitement lisible.</p>
 *
 * <p><b>Ce que ça change.</b> Les coins sont pris sur la boîte d'encre au lieu de la page. Deux
 * effets, tous deux favorables : la géométrie retombe sur le dessin réel, et le découpage envoyé ne
 * gaspille plus sa résolution en marges blanches.</p>
 *
 * <p><b>Prudence assumée.</b> Le sondage se fait sur un rendu <em>gris basse résolution</em> (aucun
 * appel réseau, quelques dizaines de ms) et le résultat n'est retenu que s'il retire une part
 * significative de la page ({@link #MAX_COVERAGE}). Une page déjà pleine, ou un scan au fond grisé,
 * retombe sur la page entière : le comportement des documents qui marchaient déjà est inchangé.</p>
 */
public final class PageInkBounds {

    /** Grand côté du rendu de sondage. On cherche où il y a de l'encre, pas à lire du texte. */
    static final int PROBE_LONG_PX = 900;

    /** Au-dessus de ce niveau de gris (0 = noir, 255 = blanc), le pixel est considéré vide. */
    static final int WHITE_LEVEL = 240;

    /**
     * Nombre minimal de pixels encrés pour qu'une ligne/colonne compte. Un scan poussiéreux a des
     * pixels sombres isolés partout ; exiger un petit groupe évite de conclure « encre » sur une
     * poussière et de retomber bêtement sur la page entière.
     */
    static final int MIN_INK_PIXELS = 3;

    /** Marge de sécurité ajoutée autour de la boîte trouvée (fraction de la page). */
    static final double MARGIN = 0.01;

    /**
     * On ne rogne que si le dessin laisse une part <b>substantielle</b> de la feuille vide, c'est-à-dire
     * s'il en couvre moins que cette fraction.
     *
     * <p>Réglé à 0.60 <b>par la mesure</b>, pas au jugé. Le défaut visé est le cas extrême : un dessin
     * qui occupe si peu la feuille que les coins tombent sur du papier blanc ({@code 13.pdf}, couverture
     * <b>0,42</b> — corrigé, 7 → 11 paires justes). Un seuil plus permissif (0,92, premier essai)
     * déclenchait aussi sur des documents à simples marges : {@code 17.pdf} (couverture <b>0,72</b>)
     * y perdait la fin de deux valeurs — {@code TITRE} passait de « PLAN DE MOBILIER NIVEAU 22 » à
     * « PLAN DE MOBILIER NIVEAU », le numéro de niveau disparaissait. Déplacer la géométrie d'un
     * document qui marche déjà ne rapporte rien et peut coûter cher : on ne rogne donc que quand le
     * problème est manifeste.</p>
     */
    static final double MAX_COVERAGE = 0.60;

    private PageInkBounds() {
    }

    /**
     * La zone encrée de la page, en fractions de page, ou {@link PdfSupport#FULL_PAGE} s'il n'y a
     * rien de significatif à rogner (ou si le sondage échoue — jamais bloquant).
     */
    public static CropRegion of(byte[] pdfBytes, int pageIndex) {
        try {
            return probe(PdfSupport.renderPageImage(pdfBytes, pageIndex, PROBE_LONG_PX));
        } catch (IOException | RuntimeException e) {
            // Sondage best-effort : en cas d'échec on garde le comportement historique (page entière).
            return PdfSupport.FULL_PAGE;
        }
    }

    /** Boîte englobante des lignes/colonnes encrées de {@code image}, en fractions. */
    static CropRegion probe(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 2 || height < 2) {
            return PdfSupport.FULL_PAGE;
        }

        int[] inkPerColumn = new int[width];
        int[] inkPerRow = new int[height];
        // Accès direct au raster plutôt que getRGB pixel par pixel : sur le rendu de localisation
        // d'un A0 (≈ 1,4 million de pixels), getRGB convertit chaque pixel dans l'espace sRGB et
        // coûte plusieurs secondes — pour une question aussi simple que « ce pixel est-il encré ? ».
        byte[] gray = grayPixels(image);
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int level = gray != null ? (gray[row + x] & 0xFF) : (image.getRGB(x, y) & 0xFF);
                if (level <= WHITE_LEVEL) {
                    inkPerColumn[x]++;
                    inkPerRow[y]++;
                }
            }
        }

        int left = firstInked(inkPerColumn, true);
        int right = firstInked(inkPerColumn, false);
        int top = firstInked(inkPerRow, true);
        int bottom = firstInked(inkPerRow, false);
        if (left < 0 || top < 0 || right <= left || bottom <= top) {
            // Page vide ou illisible : rien à rogner.
            return PdfSupport.FULL_PAGE;
        }

        double x = clamp((double) left / width - MARGIN);
        double y = clamp((double) top / height - MARGIN);
        double w = clamp((double) (right + 1) / width + MARGIN) - x;
        double h = clamp((double) (bottom + 1) / height + MARGIN) - y;
        if (w <= 0 || h <= 0 || w * h >= MAX_COVERAGE) {
            return PdfSupport.FULL_PAGE;
        }
        return new CropRegion(x, y, w, h);
    }

    /**
     * Les octets du raster quand l'image est un vrai niveaux-de-gris 8 bits (un octet par pixel,
     * sans décalage ni entrelacement), sinon {@code null} — l'appelant retombe alors sur
     * {@code getRGB}, plus lent mais universel.
     */
    private static byte[] grayPixels(BufferedImage image) {
        if (image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            return null;
        }
        java.awt.image.Raster raster = image.getRaster();
        if (!(raster.getDataBuffer() instanceof java.awt.image.DataBufferByte buffer)
                || buffer.getNumBanks() != 1) {
            return null;
        }
        byte[] data = buffer.getData();
        return data.length == image.getWidth() * image.getHeight() ? data : null;
    }

    /** Premier (ou dernier) index dont le compte d'encre atteint {@link #MIN_INK_PIXELS}, ou -1. */
    private static int firstInked(int[] counts, boolean fromStart) {
        for (int i = 0; i < counts.length; i++) {
            int index = fromStart ? i : counts.length - 1 - i;
            if (counts[index] >= MIN_INK_PIXELS) {
                return index;
            }
        }
        return -1;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
