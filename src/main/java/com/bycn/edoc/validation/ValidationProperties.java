package com.bycn.edoc.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de la validation (préfixe {@code edoc.validation}).
 *
 * @param fuzzyThreshold score minimal (échelle 0-100 de {@code FuzzySearch.ratio}) pour qu'une
 *                       valeur lue soit considérée comme désignant un code de la table de
 *                       référence. Plus strict que le seuil de classification (80) car on compare
 *                       ici des <i>codes courts</i> (« EXE », « 03 »), où un caractère de
 *                       différence pèse beaucoup plus que dans un libellé.
 *                       <b>PROVISOIRE</b> : non calibré empiriquement
 *                       ({@code data/annotations.xlsx} pas encore disponible). Reste
 *                       configurable, jamais figé en dur dans le code.
 */
@ConfigurationProperties(prefix = "edoc.validation")
public record ValidationProperties(
        @DefaultValue("85") double fuzzyThreshold
) {
}
