package com.bycn.edoc.validation;

import com.bycn.edoc.ocr.CartoucheField;
import java.util.List;

/**
 * Résultat de la validation d'une {@code ClassificationResult} contre les tables d'un projet.
 *
 * @param fields            un élément par champ demandé, dans l'ordre reçu de P3
 * @param unclassifiedPairs paires non classées, <b>reportées telles quelles depuis P3</b> : P4 ne
 *                          les regarde même pas (aucune table ne leur correspond, elles n'ont pas
 *                          de champ cible). Elles restent transportées pour ne jamais être perdues.
 */
public record ValidationResult(
        List<ValidatedField> fields,
        List<CartoucheField> unclassifiedPairs
) {

    public ValidationResult {
        fields = (fields == null) ? List.of() : List.copyOf(fields);
        unclassifiedPairs = (unclassifiedPairs == null) ? List.of() : List.copyOf(unclassifiedPairs);
    }
}
