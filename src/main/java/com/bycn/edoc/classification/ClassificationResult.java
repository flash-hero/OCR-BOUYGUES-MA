package com.bycn.edoc.classification;

import com.bycn.edoc.ocr.CartoucheField;
import java.util.List;

/**
 * Résultat de la classification d'une extraction sur les champs demandés par l'appel API.
 *
 * @param fields            un élément par champ demandé, dans l'ordre de {@code requiredFields}
 * @param unclassifiedPairs paires lues qu'aucun champ demandé n'a retenues — <b>jamais perdues</b>.
 *                          Le cartouche contient légitimement des libellés hors périmètre
 *                          (« Affaire », annuaire d'intervenants...) ; ils sont conservés pour
 *                          diagnostic et pour les phases ultérieures.
 */
public record ClassificationResult(
        List<ClassifiedField> fields,
        List<CartoucheField> unclassifiedPairs
) {

    public ClassificationResult {
        fields = (fields == null) ? List.of() : List.copyOf(fields);
        unclassifiedPairs = (unclassifiedPairs == null) ? List.of() : List.copyOf(unclassifiedPairs);
    }
}
