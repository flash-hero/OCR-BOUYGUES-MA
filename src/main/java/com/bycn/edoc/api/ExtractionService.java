package com.bycn.edoc.api;

import com.bycn.edoc.classification.ClassificationResult;
import com.bycn.edoc.classification.ClassifiedField;
import com.bycn.edoc.classification.FieldClassifier;
import com.bycn.edoc.classification.LabelNormalizer;
import com.bycn.edoc.classification.TargetField;
import com.bycn.edoc.ocr.CartoucheAnalysis;
import com.bycn.edoc.ocr.CartoucheAssignment;
import com.bycn.edoc.ocr.CartoucheExtraction;
import com.bycn.edoc.ocr.CartoucheField;
import com.bycn.edoc.ocr.ReadTarget;
import com.bycn.edoc.ocr.TwoPassCartoucheExtractor;
import com.bycn.edoc.validation.FieldValidator;
import com.bycn.edoc.validation.ReferenceEntry;
import com.bycn.edoc.validation.ReferenceTableSource;
import com.bycn.edoc.validation.ValidationResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Enchaîne le pipeline complet pour un appel API : extraction → classification → validation.
 *
 * <p>Cette classe ne contient <b>aucune</b> logique métier propre : les trois étapes restent
 * exactement celles de P2, P3 et P4. Son seul rôle est de traduire une demande générique
 * ({@link ExtractionRequest}) en ce qu'attendent ces étapes, puis de projeter le résultat sur le
 * contrat public de l'API.</p>
 *
 * <p>Le rattachement se joue à <b>deux niveaux</b>. Le modèle de lecture, qui a les champs demandés
 * sous les yeux, <em>propose</em> un rattachement paire → champ ({@link CartoucheAssignment}) — il
 * sait que « NUMERO DE DOCUMENT » désigne le champ Numéro, ce qu'une distance de chaînes ne voit
 * pas. Chaque proposition est <b>contrôlée</b> ici : la valeur doit exister parmi les paires
 * réellement lues, sinon elle est ignorée (garde anti-invention). Les champs restés sans
 * proposition passent par la classification floue classique (P3), inchangée.</p>
 *
 * <p>Le cœur reçoit des {@code byte[]} et rien d'autre — la règle « jamais de {@code Path}/{@code File}
 * dans le métier » est ce qui rend cette couche possible sans toucher au moteur : les octets viennent
 * d'un upload HTTP, et le code d'extraction ne sait pas d'où ils viennent.</p>
 */
public class ExtractionService {

    /**
     * Ressemblance minimale entre le libellé imprimé et le champ, pour qu'un rattachement proposé
     * par le modèle soit accepté. Volontairement <b>bien en dessous</b> du seuil de classification
     * (80) : tout l'intérêt de la proposition est de rattraper les libellés que la comparaison de
     * chaînes rate. Il s'agit seulement d'écarter le contresens franc — un libellé qui ne partage
     * ni un mot ni une racine avec le champ visé.
     */
    static final double MIN_ASSIGNMENT_LABEL_SCORE = 60;

    private final TwoPassCartoucheExtractor extractor;
    private final FieldClassifier classifier;
    private final FieldValidator validator;
    private final SynonymEnricher enricher;

    public ExtractionService(TwoPassCartoucheExtractor extractor, FieldClassifier classifier,
                             FieldValidator validator, SynonymEnricher enricher) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.enricher = Objects.requireNonNull(enricher, "enricher");
    }

    /**
     * @param document octets du document déposé (PDF)
     * @param request  champs attendus et leurs valeurs officielles
     * @return un résultat par champ demandé, plus tout ce qui a été lu sans être rattaché
     */
    public ExtractionResponse extract(byte[] document, ExtractionRequest request) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(request, "request");
        long startNanos = System.nanoTime();

        List<TargetField> targets = new ArrayList<>(request.fields().size());
        List<ReadTarget> readTargets = new ArrayList<>(request.fields().size());
        for (RequestedField field : request.fields()) {
            List<String> synonyms = enricher.synonymsFor(field);
            targets.add(new TargetField(field.name(), synonyms));
            readTargets.add(new ReadTarget(field.name(), synonyms));
        }

        CartoucheAnalysis analysis = extractor.extract(document, readTargets);

        ClassificationResult classification = classifyWithAssignments(analysis.extraction(), targets);
        ValidationResult validation = validator.validate(classification, officialValuesOf(request));

        return new ExtractionResponse(
                analysis.extraction().cartoucheFound(),
                analysis.mode().name(),
                analysis.corner(),
                analysis.qualityPassed(),
                validation.fields().stream().map(ExtractedField::from).toList(),
                validation.unclassifiedPairs().stream().map(ExtractedPair::from).toList(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }

    /**
     * Les propositions de rattachement du modèle d'abord (contrôlées), la classification floue pour
     * le reste. Le résultat garde l'ordre des champs demandés, et les paires prises par une
     * proposition ne sont plus offertes à la classification — un même code lu ne sert jamais deux
     * champs.
     */
    private ClassificationResult classifyWithAssignments(CartoucheExtraction extraction, List<TargetField> targets) {
        List<CartoucheField> pairs = extraction.fields();
        Map<String, TargetField> targetsByName = new LinkedHashMap<>();
        for (TargetField target : targets) {
            targetsByName.putIfAbsent(target.name(), target);
        }

        Map<String, ClassifiedField> resolved = new HashMap<>();
        boolean[] consumed = new boolean[pairs.size()];
        for (CartoucheAssignment assignment : extraction.assignments()) {
            TargetField target = targetsByName.get(assignment.field());
            if (target == null || resolved.containsKey(target.name())
                    || assignment.value() == null || assignment.value().isBlank()) {
                continue;
            }
            int pairIndex = readPairIndex(pairs, consumed, assignment);
            if (pairIndex < 0) {
                // Garde anti-invention : la valeur proposée n'existe pas parmi les paires lues.
                // On ignore la proposition ; le champ repasse par la classification floue.
                continue;
            }
            CartoucheField pair = pairs.get(pairIndex);
            Scored scored = bestSynonymFor(pair.label(), target);
            if (scored.score() < MIN_ASSIGNMENT_LABEL_SCORE) {
                // Garde anti-contresens : le libellé imprimé n'a rien à voir avec ce champ. Mesuré
                // en conditions réelles — « PROJET = FUTUR PALAIS DE JUSTICE DE PARIS » proposé pour
                // le champ Phase, valeur bien lue mais rangée dans le mauvais champ. Le seuil est
                // BAS pour laisser passer ce que la proposition apporte justement : les libellés
                // composés (« NUMERO DE DOCUMENT » pour Numéro, « Zone / Niveau » pour Niveau).
                continue;
            }
            consumed[pairIndex] = true;
            resolved.put(target.name(),
                    ClassifiedField.toReview(target.name(), pair, scored.score(), scored.synonym()));
        }

        List<TargetField> remainingTargets = new ArrayList<>();
        for (TargetField target : targets) {
            if (!resolved.containsKey(target.name())) {
                remainingTargets.add(target);
            }
        }
        List<CartoucheField> remainingPairs = new ArrayList<>();
        for (int p = 0; p < pairs.size(); p++) {
            if (!consumed[p]) {
                remainingPairs.add(pairs.get(p));
            }
        }
        ClassificationResult fuzzy = classifier.classifyTargets(
                new CartoucheExtraction(extraction.cartoucheFound(), remainingPairs), remainingTargets);

        Map<String, ClassifiedField> fromFuzzy = new HashMap<>();
        for (ClassifiedField field : fuzzy.fields()) {
            fromFuzzy.putIfAbsent(field.targetField(), field);
        }
        List<ClassifiedField> orderedFields = new ArrayList<>(targets.size());
        for (TargetField target : targets) {
            ClassifiedField field = resolved.get(target.name());
            if (field == null) {
                field = fromFuzzy.getOrDefault(target.name(), ClassifiedField.missing(target.name()));
            }
            orderedFields.add(field);
        }
        return new ClassificationResult(orderedFields, fuzzy.unclassifiedPairs());
    }

    /**
     * La paire lue que désigne une proposition du modèle, ou {@code -1} si aucune ne porte cette
     * valeur — le cas exact qu'on refuse de croire. À valeur égale sur plusieurs paires (un « A »
     * d'indice et un « A » de zone), celle dont le libellé correspond aussi est préférée.
     */
    private static int readPairIndex(List<CartoucheField> pairs, boolean[] consumed, CartoucheAssignment assignment) {
        String wantedValue = LabelNormalizer.normalize(assignment.value());
        String wantedLabel = LabelNormalizer.normalize(assignment.label());
        int valueOnly = -1;
        for (int p = 0; p < pairs.size(); p++) {
            if (consumed[p] || !LabelNormalizer.normalize(pairs.get(p).value()).equals(wantedValue)) {
                continue;
            }
            if (LabelNormalizer.normalize(pairs.get(p).label()).equals(wantedLabel)) {
                return p;
            }
            if (valueOnly < 0) {
                valueOnly = p;
            }
        }
        return valueOnly;
    }

    /**
     * Score et synonyme publiés pour une proposition du modèle : la ressemblance floue réelle entre
     * le libellé imprimé et le meilleur libellé attendu — même sous le seuil. C'est voulu : un score
     * bas sur un champ rempli montre précisément ce que le rattachement sémantique apporte par
     * rapport à la distance de chaînes, et reste un signal honnête pour le diagnostic.
     */
    private static Scored bestSynonymFor(String rawLabel, TargetField target) {
        String label = LabelNormalizer.normalize(rawLabel);
        double bestScore = 0;
        String bestSynonym = target.name();
        for (String synonym : target.synonyms()) {
            String normalized = LabelNormalizer.normalize(synonym);
            if (normalized.isEmpty()) {
                continue;
            }
            double score = FieldClassifier.similarity(label, normalized);
            if (score > bestScore) {
                bestScore = score;
                bestSynonym = synonym;
            }
        }
        return new Scored(bestScore, bestSynonym);
    }

    private record Scored(double score, String synonym) {
    }

    /**
     * Les valeurs officielles portées par la demande, vues comme une table de référence.
     *
     * <p>Un champ sans valeurs déclarées renvoie une liste vide : « pas de table », donc rien à
     * valider — jamais « valide par défaut », jamais un rejet (règle D11).</p>
     */
    private static ReferenceTableSource officialValuesOf(ExtractionRequest request) {
        Map<String, List<ReferenceEntry>> byField = new HashMap<>();
        for (RequestedField field : request.fields()) {
            List<ReferenceEntry> entries = field.allowedValues().stream()
                    .filter(value -> value.code() != null && !value.code().isBlank())
                    .map(value -> new ReferenceEntry(value.code(), value.libelle()))
                    .toList();
            if (!entries.isEmpty()) {
                byField.put(field.name(), entries);
            }
        }
        return targetField -> byField.getOrDefault(targetField, List.of());
    }
}
