package com.bycn.edoc.classification;

import java.util.ArrayList;
import java.util.List;

/**
 * Les synonymes de libellé déclarés pour un champ cible dans {@code schema_fields.yaml}.
 *
 * @param confirmed  synonymes observés sur le corpus, actifs en permanence
 * @param hypothesis synonymes plausibles mais non tranchés avec l'encadrant (Q3) : actifs
 *                   uniquement si {@code edoc.classification.use-hypothesis-synonyms} est vrai
 */
public record SynonymEntry(List<String> confirmed, List<String> hypothesis) {

    public SynonymEntry {
        confirmed = (confirmed == null) ? List.of() : List.copyOf(confirmed);
        hypothesis = (hypothesis == null) ? List.of() : List.copyOf(hypothesis);
    }

    /**
     * Les synonymes réellement utilisables pour le matching, selon le drapeau de configuration.
     * Les {@code hypothesis} ne sont ajoutés que si {@code useHypothesis} est vrai.
     */
    public List<String> activeSynonyms(boolean useHypothesis) {
        if (!useHypothesis) {
            return confirmed;
        }
        List<String> active = new ArrayList<>(confirmed);
        active.addAll(hypothesis);
        return List.copyOf(active);
    }
}
