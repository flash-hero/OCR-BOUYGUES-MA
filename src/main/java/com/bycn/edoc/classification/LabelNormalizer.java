package com.bycn.edoc.classification;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Met un libellé sous forme comparable avant la correspondance floue.
 *
 * <p><b>Pourquoi c'est indispensable</b> : {@code FuzzySearch.ratio} est sensible à la casse
 * <i>et</i> aux accents. Mesuré sur la bibliothèque réelle, sans normalisation :
 * {@code ratio("NUM", "Num") = 33}, {@code ratio("LEVEL", "Level") = 20},
 * {@code ratio("EMETTEUR", "Émetteur") = 0} — tous très en dessous du seuil. Or « NUM » et
 * « LEVEL » sont précisément les variantes que le corpus contient (voir CLAUDE.md) : comparer
 * les libellés bruts renverrait MISSING sur des champs pourtant bien présents.</p>
 *
 * <p>La même normalisation est appliquée aux deux côtés (libellé lu et synonyme), donc elle
 * s'annule et ne fausse pas le score. Les valeurs d'origine sont conservées telles quelles
 * dans {@link ClassifiedField} ({@code rawLabel}, {@code matchedSynonym}).</p>
 */
final class LabelNormalizer {

    private LabelNormalizer() {
    }

    /**
     * Replie les accents, passe en minuscules, compacte les espaces et retire les deux-points
     * finaux (« N° Doc : » et « N° Doc » désignent le même libellé).
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String unaccented = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return unaccented.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim()
                .replaceAll(":+$", "")
                .trim();
    }
}
