package com.bycn.edoc.api;

import java.util.List;

/**
 * Réponse complète de l'API d'extraction.
 *
 * <p>Elle porte à la fois le résultat métier ({@code fields}) et de quoi <em>juger</em> ce résultat
 * ({@code mode}, {@code corner}, {@code qualityPassed}) : conformément à la règle « un contrôle qui
 * dit oui ne veut pas dire que c'est correct », l'appelant doit pouvoir signaler une lecture
 * incertaine plutôt que la présenter comme sûre.</p>
 *
 * @param cartoucheFound    le moteur a-t-il estimé avoir trouvé un cartouche
 * @param mode              chemin suivi : {@code SINGLE_PAGE}, {@code TWO_PASS_CROP}, {@code NEEDS_TILING}
 * @param corner            zone retenue sur un grand plan ({@code null} en lecture pleine page)
 * @param qualityPassed     l'extraction retenue a passé le contrôle de plausibilité. À {@code false},
 *                          les valeurs restent exploitables mais doivent être présentées comme
 *                          « à vérifier » — c'est le repli « meilleur essai » du moteur.
 * @param fields            un élément par champ demandé, dans l'ordre de la demande
 * @param unclassifiedPairs paires lues rattachées à aucun champ demandé (voir {@link ExtractedPair})
 * @param durationMs        durée totale du traitement, pour le suivi de latence
 */
public record ExtractionResponse(
        boolean cartoucheFound,
        String mode,
        String corner,
        boolean qualityPassed,
        List<ExtractedField> fields,
        List<ExtractedPair> unclassifiedPairs,
        long durationMs) {

    public ExtractionResponse {
        fields = (fields == null) ? List.of() : List.copyOf(fields);
        unclassifiedPairs = (unclassifiedPairs == null) ? List.of() : List.copyOf(unclassifiedPairs);
    }
}
