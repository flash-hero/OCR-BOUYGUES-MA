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

    /**
     * Rectangle construit depuis les quatre bords d'une boîte englobante (fractions), ou {@code null}
     * si la boîte est dégénérée : bords hors de [0;1] après serrage, inversés, ou plus petits que
     * {@code minSide} — une boîte de quelques pourcents ne peut pas contenir un cartouche entier,
     * c'est une localisation ratée qu'il vaut mieux rejeter que découper.
     */
    public static CropRegion fromBox(double left, double top, double right, double bottom, double minSide) {
        double l = clamp01(left);
        double t = clamp01(top);
        double r = clamp01(right);
        double b = clamp01(bottom);
        if (r - l < minSide || b - t < minSide) {
            return null;
        }
        return new CropRegion(l, t, r - l, b - t);
    }

    /**
     * Ce rectangle élargi d'une marge sur chaque côté (serré aux bords de la page). La marge absorbe
     * une boîte englobante légèrement trop serrée — sans elle, une ligne du cartouche coupée au bord
     * du découpage serait perdue. Elle reste petite à dessein : plus il y a de texte dans l'image,
     * moins la transcription est fidèle (mesuré, voir application.yml sur corner-fraction).
     */
    public CropRegion expanded(double margin) {
        double nx = clamp01(x - margin);
        double ny = clamp01(y - margin);
        double nr = clamp01(x + w + margin);
        double nb = clamp01(y + h + margin);
        return new CropRegion(nx, ny, nr - nx, nb - ny);
    }

    public boolean isFullPage() {
        return x == 0 && y == 0 && w == 1 && h == 1;
    }

    /**
     * Ce rectangle réinterprété <b>à l'intérieur</b> de {@code outer} : les fractions, jusqu'ici
     * relatives à la page, deviennent relatives à {@code outer}. Sert à prendre les coins de la zone
     * réellement dessinée plutôt que ceux de la feuille (voir {@link PageInkBounds}), et à ramener en
     * fractions de page une boîte localisée sur l'image d'une sous-région.
     */
    public CropRegion within(CropRegion outer) {
        if (outer == null || outer.isFullPage()) {
            return this;
        }
        return new CropRegion(
                outer.x + x * outer.w,
                outer.y + y * outer.h,
                w * outer.w,
                h * outer.h);
    }

    /**
     * Zone de la grille 3×3 (libellés de {@link CartoucheLocationSchema#ZONES}) où tombe le centre de
     * ce rectangle — uniquement pour les journaux et la préférence du repli par coins.
     */
    public String zoneLabel() {
        double cx = x + w / 2;
        double cy = y + h / 2;
        String col = cx < 1.0 / 3 ? "left" : (cx > 2.0 / 3 ? "right" : "center");
        String row = cy < 1.0 / 3 ? "top" : (cy > 2.0 / 3 ? "bottom" : "middle");
        return switch (row) {
            case "top" -> col.equals("center") ? "top-center" : "top-" + col;
            case "bottom" -> col.equals("center") ? "bottom-center" : "bottom-" + col;
            default -> col.equals("center") ? "center" : col;
        };
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
