package com.bycn.edoc.classification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de la classification (préfixe {@code edoc.classification}).
 *
 * @param fuzzyThreshold        score minimal (échelle 0-100 de {@code FuzzySearch.ratio}) pour
 *                              qu'un libellé lu soit considéré comme désignant un champ.
 *                              <b>PROVISOIRE</b> : valeur non calibrée empiriquement
 *                              ({@code data/annotations.xlsx} pas encore disponible). Reste
 *                              configurable, jamais figée en dur dans le code.
 * @param useHypothesisSynonyms active les synonymes {@code hypothesis} de la bibliothèque.
 *                              Faux par défaut : Q3 non tranchée avec l'encadrant (voir
 *                              {@code docs/instruction.md}).
 */
@ConfigurationProperties(prefix = "edoc.classification")
public record ClassificationProperties(
        @DefaultValue("80") double fuzzyThreshold,
        @DefaultValue("false") boolean useHypothesisSynonyms
) {
}
