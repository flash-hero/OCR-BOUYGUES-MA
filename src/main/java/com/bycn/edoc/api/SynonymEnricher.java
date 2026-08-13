package com.bycn.edoc.api;

import com.bycn.edoc.classification.ClassificationProperties;
import com.bycn.edoc.classification.LabelNormalizer;
import com.bycn.edoc.classification.SchemaFieldsRegistry;
import com.bycn.edoc.classification.SynonymEntry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import me.xdrop.fuzzywuzzy.FuzzySearch;

/**
 * Complète les libellés fournis par l'appelant avec les variantes déjà constatées sur le corpus.
 *
 * <p><b>Le problème.</b> eDoc ne connaît qu'un libellé long et un libellé court par champ
 * (« Phase » / « Pha »). Le cartouche imprimé, lui, écrit ce qu'il veut : « N° Doc », « N° GED »,
 * « Level », « NUM »… Ces variantes-là sont justement celles que {@code schema_fields.yaml} a
 * patiemment accumulées en observant le corpus réel.</p>
 *
 * <p><b>Le principe.</b> Pour chaque champ demandé, on part de ses libellés eDoc (toujours
 * prioritaires, jamais remplacés) ; si l'un d'eux désigne visiblement un champ déjà connu du
 * référentiel — correspondance floue, au seuil de classification — on ajoute les synonymes de ce
 * champ. Le référentiel <em>enrichit</em> donc la demande, il ne la contraint jamais : un champ
 * inconnu du yaml (« Bâtiment » d'un projet donné) fonctionne parfaitement avec ses seuls libellés
 * eDoc.</p>
 *
 * <p><b>Statut de cette heuristique : non mesurée.</b> Elle est bâtie sur un raisonnement, pas sur
 * une mesure de précision (la vérité terrain {@code annotations.xlsx} n'existe pas encore). Elle est
 * donc désactivable par configuration ({@code edoc.api.enrich-synonyms=false}) sans toucher au code.
 * À évaluer avec le futur harnais P6 avant d'être présentée comme un gain établi.</p>
 */
public class SynonymEnricher {

    private final SchemaFieldsRegistry registry;
    private final ClassificationProperties properties;
    private final boolean enabled;

    public SynonymEnricher(SchemaFieldsRegistry registry, ClassificationProperties properties, boolean enabled) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.enabled = enabled;
    }

    /**
     * Libellés à utiliser pour rechercher ce champ dans le cartouche : ceux de l'appelant d'abord
     * (ordre préservé), puis ceux du référentiel si un champ connu y correspond.
     */
    public List<String> synonymsFor(RequestedField field) {
        LinkedHashSet<String> synonyms = new LinkedHashSet<>();
        for (String label : field.labels()) {
            if (label != null && !label.isBlank()) {
                synonyms.add(label);
            }
        }
        if (!enabled || synonyms.isEmpty()) {
            return List.copyOf(synonyms);
        }
        for (String knownField : registry.fieldNames()) {
            SynonymEntry entry = registry.find(knownField).orElse(null);
            if (entry == null) {
                continue;
            }
            List<String> known = entry.activeSynonyms(properties.useHypothesisSynonyms());
            if (designatesSameField(synonyms, known)) {
                synonyms.addAll(known);
            }
        }
        return List.copyOf(synonyms);
    }

    /**
     * Un libellé de l'appelant désigne-t-il le même champ qu'un des synonymes connus ? Comparaison
     * floue sur les deux côtés normalisés — mêmes règles que la classification elle-même, pour ne
     * pas introduire ici une sensibilité à la casse ou aux accents que P3 a justement éliminée.
     */
    private boolean designatesSameField(Iterable<String> callerLabels, List<String> knownSynonyms) {
        for (String label : callerLabels) {
            String normalizedLabel = LabelNormalizer.normalize(label);
            if (normalizedLabel.isEmpty()) {
                continue;
            }
            for (String known : knownSynonyms) {
                String normalizedKnown = LabelNormalizer.normalize(known);
                if (normalizedKnown.isEmpty()) {
                    continue;
                }
                if (FuzzySearch.ratio(normalizedLabel, normalizedKnown) >= properties.fuzzyThreshold()) {
                    return true;
                }
            }
        }
        return false;
    }
}
