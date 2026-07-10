package com.bycn.edoc.ocr;

/**
 * Rectangle de découpage exprimé en <b>fractions</b> de la page (origine en haut-gauche, y vers le
 * bas), calculé à partir de la zone grossière renvoyée par la passe 1.
 *
 * <p>On prend une zone <b>généreuse</b> (pas serrée) pour absorber l'imprécision de la localisation :
 * un carré de {@code cornerFrac} depuis le coin identifié, ou une bande plus large ({@code bandFrac})
 * pour les cartouches centrés / le long d'un bord.</p>
 *
 * @param x fraction horizontale du bord gauche du découpage (0 = bord gauche de la page)
 * @param y fraction verticale du bord haut du découpage (0 = haut de la page)
 * @param w largeur du découpage en fraction de la largeur de page
 * @param h hauteur du découpage en fraction de la hauteur de page
 */
public record CropRegion(double x, double y, double w, double h) {

    /**
     * Traduit une zone (voir {@link CartoucheLocationSchema#ZONES}) en rectangle fractionnaire.
     *
     * @param corner     la zone renvoyée par la passe 1
     * @param cornerFrac taille du carré pris depuis un coin (ex. 0.40 = 40 %)
     * @param bandFrac   largeur/hauteur de la bande pour les zones centrées/latérales (ex. 0.70)
     */
    public static CropRegion forCorner(String corner, double cornerFrac, double bandFrac) {
        double f = cornerFrac;
        double b = bandFrac;
        String c = (corner == null) ? "" : corner.trim().toLowerCase();
        return switch (c) {
            case "top-left" -> new CropRegion(0, 0, f, f);
            case "top-right" -> new CropRegion(1 - f, 0, f, f);
            case "bottom-left" -> new CropRegion(0, 1 - f, f, f);
            case "bottom-right" -> new CropRegion(1 - f, 1 - f, f, f);
            case "top-center" -> new CropRegion((1 - b) / 2, 0, b, f);
            case "bottom-center" -> new CropRegion((1 - b) / 2, 1 - f, b, f);
            case "left" -> new CropRegion(0, (1 - b) / 2, f, b);
            case "right" -> new CropRegion(1 - f, (1 - b) / 2, f, b);
            case "center" -> new CropRegion((1 - b) / 2, (1 - b) / 2, b, b);
            // Zone inconnue : on ne devrait pas arriver ici (l'orchestrateur bascule en tuiles),
            // mais par sécurité on renvoie la page entière.
            default -> new CropRegion(0, 0, 1, 1);
        };
    }

    public boolean isFullPage() {
        return x == 0 && y == 0 && w == 1 && h == 1;
    }
}
