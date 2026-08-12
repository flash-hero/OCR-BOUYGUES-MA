package com.bycn.edoc.api;

import com.bycn.edoc.ocr.CartoucheField;

/**
 * Une paire libellé/valeur lue dans le cartouche mais rattachée à aucun champ demandé.
 *
 * <p>Renvoyée volontairement : le cartouche contient légitimement des informations hors périmètre
 * (Affaire, Échelle, annuaire d'intervenants…). Les exposer permet à l'appelant de les afficher ou
 * de les exploiter plus tard — <b>rien de ce qui a été lu n'est jamais perdu en silence</b>.</p>
 */
public record ExtractedPair(String label, String value) {

    public static ExtractedPair from(CartoucheField pair) {
        return new ExtractedPair(pair.label(), pair.value());
    }
}
