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

    /**
     * Chemin historique : les champs sont désignés par leur seul nom, et leurs synonymes sont
     * résolus dans {@code schema_fields.yaml}. Un champ absent du référentiel sort
     * {@link FieldStatus#MISSING} (jamais une erreur), comportement inchangé.
     */
    public ClassificationResult classify(CartoucheExtraction extraction, List<String> requiredFields) {
        List<String> names = (requiredFields == null) ? List.of() : requiredFields;
        List<TargetField> targets = new ArrayList<>(names.size());
        for (String name : names) {
            SynonymEntry entry = registry.find(name).orElse(null);
            if (entry == null) {
                log.warn("Champ cible « {} » absent de {} : classé MISSING.",
                        name, SchemaFieldsRegistry.DEFAULT_RESOURCE);
                targets.add(new TargetField(name, List.of()));
            } else {
                targets.add(new TargetField(name, entry.activeSynonyms(properties.useHypothesisSynonyms())));
            }
        }
        return classifyTargets(extraction, targets);
    }

    /**
     * Chemin générique : l'appelant fournit lui-même les libellés attendus de chaque champ
     * (voir {@link TargetField}). Aucune lecture de {@code schema_fields.yaml} ici — c'est ce
     * qui permet de servir un projet eDoc dont les champs ne figurent dans aucun référentiel
     * statique. La logique d'assignation est identique dans les deux cas.
     */
    public ClassificationResult classifyTargets(CartoucheExtraction extraction, List<TargetField> requiredFields) {
        List<CartoucheField> pairs = (extraction == null) ? List.of() : extraction.fields();
        List<TargetField> targets = (requiredFields == null) ? List.of() : List.copyOf(requiredFields);

        List<Candidate> candidates = collectCandidates(targets, pairs);
        // Score décroissant, puis — à score égal — une paire RENSEIGNÉE avant une paire à valeur
        // vide. Un cartouche contient souvent deux fois le même libellé : l'en-tête vide d'un
        // tableau de révisions (« DATE | MODIFICATION | INDICE », colonnes non remplies dans la
        // zone lue) et la vraie ligne du cartouche. Sans ce départage, c'est l'ordre de lecture qui
        // tranchait, et l'en-tête vide — souvent placé plus haut — l'emportait : le champ sortait
        // vide alors que la valeur était juste en dessous (observé sur 20.pdf, DATE 13/07/2012).
        // Le reste départage par ordre de déclaration : deux exécutions identiques, même résultat.
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Comparator.comparing(Candidate::valued).reversed())
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

    private List<Candidate> collectCandidates(List<TargetField> targets, List<CartoucheField> pairs) {
        List<Candidate> candidates = new ArrayList<>();
        for (int t = 0; t < targets.size(); t++) {
            List<String> synonyms = targets.get(t).synonyms();
            if (synonyms.isEmpty()) {
                continue;   // aucun libellé attendu : le champ sortira MISSING
            }
            for (int p = 0; p < pairs.size(); p++) {
                Candidate best = bestMatch(t, p, pairs.get(p), synonyms);
                if (best != null) {
                    candidates.add(best);
                }
            }
        }
        return candidates;
    }

    /**
     * Rang d'une correspondance par mots sous la correspondance sur la chaîne entière. Sans ce cran,
     * un libellé lu « Doc » obtiendrait 100 face à « Doc » ET face à « N° Doc », et l'égalité se
     * trancherait par le simple ordre de déclaration des champs.
     */
    private static final double TOKEN_SET_PENALTY = 1;

    /**
     * Ressemblance entre un libellé lu et un synonyme attendu (tous deux déjà normalisés).
     *
     * <p>La chaîne entière d'abord ({@code ratio}) ; en complément, la comparaison par <b>ensembles
     * de mots</b> ({@code tokenSetRatio}), classée juste en dessous. Elle couvre les libellés
     * <b>composés</b>, très fréquents sur les cartouches réels : « NUMERO DE DOCUMENT » contient
     * mot pour mot « Numéro » mais ne marque que 50 en chaîne entière — sous le seuil, champ perdu
     * alors que l'information est imprimée. Mesuré sur le corpus : les couples qui ne doivent PAS
     * se rattacher (Auteur/Numéro, Date/Type, Indice/Niveau) plafonnent très en dessous du seuil,
     * la marge est large.</p>
     */
    public static double similarity(String normalizedLabel, String normalizedSynonym) {
        double whole = FuzzySearch.ratio(normalizedLabel, normalizedSynonym);
        double tokens = FuzzySearch.tokenSetRatio(normalizedLabel, normalizedSynonym) - TOKEN_SET_PENALTY;
        return Math.max(whole, tokens);
    }

    /** Meilleur synonyme du champ pour cette paire, ou {@code null} si aucun n'atteint le seuil. */
    private Candidate bestMatch(int targetIndex, int pairIndex, CartoucheField pair, List<String> synonyms) {
        String label = LabelNormalizer.normalize(pair.label());
        if (label.isEmpty()) {
            return null;
        }
        boolean valued = pair.value() != null && !pair.value().isBlank();
        Candidate best = null;
        for (String synonym : synonyms) {
            String normalized = LabelNormalizer.normalize(synonym);
            if (normalized.isEmpty()) {
                continue;
            }
            double score = similarity(label, normalized);
            if (score >= properties.fuzzyThreshold() && (best == null || score > best.score())) {
                best = new Candidate(targetIndex, pairIndex, score, synonym, valued);
            }
        }
        return best;
    }

    private static List<ClassifiedField> classifiedFields(List<TargetField> targets, List<CartoucheField> pairs,
                                                          Map<Integer, Candidate> assigned) {
        List<ClassifiedField> classified = new ArrayList<>(targets.size());
        for (int t = 0; t < targets.size(); t++) {
            String name = targets.get(t).name();
            Candidate candidate = assigned.get(t);
            classified.add(candidate == null
                    ? ClassifiedField.missing(name)
                    : ClassifiedField.toReview(name, pairs.get(candidate.pairIndex()),
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

    /**
     * Un appariement possible (champ cible, paire lue).
     *
     * @param valued la paire porte-t-elle une valeur non vide ? Départage deux paires dont le
     *               libellé matche aussi bien : voir {@link FieldClassifier#classifyTargets}.
     */
    private record Candidate(int targetIndex, int pairIndex, double score, String synonym, boolean valued) {
    }
}
