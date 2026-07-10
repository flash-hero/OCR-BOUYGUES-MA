package com.bycn.edoc.ocr;

import java.util.List;

/**
 * Résultat d'extraction <b>générique</b> d'un cartouche : aucun champ n'est nommé à l'avance,
 * on renvoie simplement toutes les paires libellé/valeur trouvées, telles qu'imprimées.
 *
 * @param cartoucheFound {@code true} si un cartouche a été localisé sur le document
 * @param fields         toutes les paires lues (liste vide si aucun cartouche)
 */
public record CartoucheExtraction(boolean cartoucheFound, List<CartoucheField> fields) {

    public CartoucheExtraction {
        fields = (fields == null) ? List.of() : List.copyOf(fields);
    }
}
