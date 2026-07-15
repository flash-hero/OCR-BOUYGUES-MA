package com.bycn.edoc.classification;

import com.bycn.edoc.ocr.CartoucheExtraction;
import com.bycn.edoc.ocr.CartoucheField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P3 — range chaque paire libellé/valeur lue sur le champ cible demandé par l'appel API.
 *
 * <p>Purement déterministe : aucune IA, aucun appel réseau, aucun repli externe. Seule la
 * correspondance floue sur le libellé décide (jamais d'égalité stricte — règle non négociable :
 * le corpus contient « NUM » pour NUMERO, « LEVEL » pour NIVEAU).</p>
 *
 * <p><b>Assignation gloutonne globale</b> et non « premier arrivé, premier servi » : tous les
 * couples (champ, paire) au-dessus du seuil sont mis en concurrence puis attribués par score
 * décroissant. Sans cela, deux champs cibles peuvent se disputer la même paire et le résultat
 * dépendrait de l'ordre d'itération de {@code requiredFields} — un champ servi en premier
 * prendrait une paire qu'un autre revendique plus fortement, alors qu'il avait lui-même une
 * seconde option acceptable.</p>
 */
public class FieldClassifier {

    private static final Logger log = LoggerFactory.getLogger(FieldClassifier.class);

    private final SchemaFieldsRegistry registry;
    private final ClassificationProperties properties;

    public FieldClassifier(SchemaFieldsRegistry registry, ClassificationProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public ClassificationResult classify(CartoucheExtraction extraction, List<String> requiredFields) {
        List<CartoucheField> pairs = (extraction == null) ? List.of() : extraction.fields();
        List<String> targets = (requiredFields == null) ? List.of() : List.copyOf(requiredFields);

        List<Candidate> candidates = collectCandidates(targets, pairs);
        // Score décroissant ; les égalités sont départagées par l'ordre de déclaration, pour que
        // deux exécutions sur les mêmes entrées donnent toujours exactement le même résultat.
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparingInt(Candidate::targetIndex)
                .thenComparingInt(Candidate::pairIndex));

        Map<Integer, Candidate> assigned = new HashMap<>();
        boolean[] pairTaken = new boolean[pairs.size()];
        for (Candidate candidate : candidates) {
            if (assigned.containsKey(candidate.targetIndex()) || pairTaken[candidate.pairIndex()]) {
                continue;
            }
            assigned.put(candidate.targetIndex(), candidate);
            pairTaken[candidate.pairIndex()] = true;
        }

        return new ClassificationResult(
                classifiedFields(targets, pairs, assigned),
                leftoverPairs(pairs, pairTaken));
    }

    private List<Candidate> collectCandidates(List<String> targets, List<CartoucheField> pairs) {
        List<Candidate> candidates = new ArrayList<>();
        for (int t = 0; t < targets.size(); t++) {
            String target = targets.get(t);
            SynonymEntry entry = registry.find(target).orElse(null);
            if (entry == null) {
                log.warn("Champ cible « {} » absent de {} : classé MISSING.",
                        target, SchemaFieldsRegistry.DEFAULT_RESOURCE);
                continue;
            }
            List<String> synonyms = entry.activeSynonyms(properties.useHypothesisSynonyms());
            for (int p = 0; p < pairs.size(); p++) {
                Candidate best = bestMatch(t, p, pairs.get(p), synonyms);
                if (best != null) {
                    candidates.add(best);
                }
            }
        }
        return candidates;
    }

    /** Meilleur synonyme du champ pour cette paire, ou {@code null} si aucun n'atteint le seuil. */
    private Candidate bestMatch(int targetIndex, int pairIndex, CartoucheField pair, List<String> synonyms) {
        String label = LabelNormalizer.normalize(pair.label());
        if (label.isEmpty()) {
            return null;
        }
        Candidate best = null;
        for (String synonym : synonyms) {
            String normalized = LabelNormalizer.normalize(synonym);
            if (normalized.isEmpty()) {
                continue;
            }
            double score = FuzzySearch.ratio(label, normalized);
            if (score >= properties.fuzzyThreshold() && (best == null || score > best.score())) {
                best = new Candidate(targetIndex, pairIndex, score, synonym);
            }
        }
        return best;
    }

    private static List<ClassifiedField> classifiedFields(List<String> targets, List<CartoucheField> pairs,
                                                          Map<Integer, Candidate> assigned) {
        List<ClassifiedField> classified = new ArrayList<>(targets.size());
        for (int t = 0; t < targets.size(); t++) {
            Candidate candidate = assigned.get(t);
            classified.add(candidate == null
                    ? ClassifiedField.missing(targets.get(t))
                    : ClassifiedField.toReview(targets.get(t), pairs.get(candidate.pairIndex()),
                            candidate.score(), candidate.synonym()));
        }
        return classified;
    }

    private static List<CartoucheField> leftoverPairs(List<CartoucheField> pairs, boolean[] pairTaken) {
        List<CartoucheField> leftover = new ArrayList<>();
        for (int p = 0; p < pairs.size(); p++) {
            if (!pairTaken[p]) {
                leftover.add(pairs.get(p));
            }
        }
        return leftover;
    }

    private record Candidate(int targetIndex, int pairIndex, double score, String synonym) {
    }
}
