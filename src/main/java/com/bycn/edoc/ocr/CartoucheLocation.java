package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Résultat de la <b>passe 1</b> (localisation grossière) : sur un grand plan, on ne demande pas le
 * contenu du cartouche mais seulement sa <em>zone</em> approximative sur la page — une estimation
 * grossière survit bien mieux au redimensionnement de l'image que la lecture fine du texte.
 *
 * @param cartoucheFound {@code true} si un cartouche est repéré sur la page
 * @param corner         zone approximative (voir {@link CartoucheLocationSchema#ZONES}), ou {@code unknown}
 * @param raw            l'annotation JSON brute de la passe 1 (peut être {@code null})
 */
public record CartoucheLocation(boolean cartoucheFound, String corner, JsonNode raw) {

    /** {@code true} si la passe 1 n'a pas su situer le cartouche (zone {@code unknown} ou vide). */
    public boolean isUnknown() {
        return corner == null || corner.isBlank() || corner.trim().equalsIgnoreCase("unknown");
    }
}
