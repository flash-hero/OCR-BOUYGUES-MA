package com.bycn.edoc.ocr;

import java.util.List;

/**
 * Résultat d'extraction <b>générique</b> d'un cartouche : aucun champ n'est nommé à l'avance,
 * on renvoie simplement toutes les paires libellé/valeur trouvées, telles qu'imprimées.
 *
 * <p>Quand l'appel fournit des champs cibles (voir {@link ReadTarget}), le modèle ajoute des
 * <b>propositions de rattachement</b> — mais {@code fields} reste toujours la lecture complète :
 * les propositions s'ajoutent, elles ne remplacent ni ne filtrent jamais rien.</p>
 *
 * @param cartoucheFound {@code true} si un cartouche a été localisé sur le document
 * @param fields         toutes les paires lues (liste vide si aucun cartouche)
 * @param assignments    propositions de rattachement paire → champ demandé (vide si aucun champ
 *                       cible fourni, ou si le modèle n'en propose aucune)
 */
public record CartoucheExtraction(boolean cartoucheFound, List<CartoucheField> fields,
                                  List<CartoucheAssignment> assignments) {

    public CartoucheExtraction {
        fields = (fields == null) ? List.of() : List.copyOf(fields);
        assignments = (assignments == null) ? List.of() : List.copyOf(assignments);
    }

    /** Forme historique : extraction sans proposition de rattachement. */
    public CartoucheExtraction(boolean cartoucheFound, List<CartoucheField> fields) {
        this(cartoucheFound, fields, List.of());
    }
}
