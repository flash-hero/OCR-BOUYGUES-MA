package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Résultat de la <b>passe 1</b> (localisation) : où est le cartouche sur l'image envoyée, sans en
 * lire le contenu — une position survit bien mieux au redimensionnement de l'image que le texte fin.
 *
 * @param cartoucheFound {@code true} si un cartouche est repéré sur la page
 * @param corner         zone approximative (voir {@link CartoucheLocationSchema#ZONES}), ou {@code unknown}
 * @param box            boîte englobante du cartouche, en fractions de l'<b>image envoyée</b>
 *                       (nul si absente ou dégénérée — l'appelant bascule alors sur le repli par coins)
 * @param rotation       rotation à appliquer au découpage pour que le texte soit droit
 *                       (voir {@link CartoucheLocationSchema#ROTATIONS} ; {@code none} par défaut)
 * @param raw            l'annotation JSON brute de la passe 1 (peut être {@code null})
 */
public record CartoucheLocation(boolean cartoucheFound, String corner, CropRegion box,
                                String rotation, JsonNode raw) {

    /** Forme historique (zone seule) : localisation sans boîte, texte supposé droit. */
    public CartoucheLocation(boolean cartoucheFound, String corner, JsonNode raw) {
        this(cartoucheFound, corner, null, "none", raw);
    }

    /** {@code true} si la passe 1 n'a pas su situer le cartouche (zone {@code unknown} ou vide). */
    public boolean isUnknown() {
        return corner == null || corner.isBlank() || corner.trim().equalsIgnoreCase("unknown");
    }

    /** {@code true} si la passe 1 fournit une boîte exploitable pour un découpage ciblé. */
    public boolean hasBox() {
        return cartoucheFound && box != null;
    }
}
