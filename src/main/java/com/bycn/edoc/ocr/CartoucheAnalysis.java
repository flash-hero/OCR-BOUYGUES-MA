package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Résultat de l'orchestration {@link TwoPassCartoucheExtractor} : selon le format du document, il
 * décrit le chemin suivi et l'extraction obtenue.
 *
 * <ul>
 *   <li>{@link Mode#SINGLE_PAGE} — format standard (A4/A3) : lecture pleine page directe.</li>
 *   <li>{@link Mode#TWO_PASS_CROP} — grand plan : passe 1 (localisation) + passe 2 (extraction sur
 *       le découpage plein résolution du coin), avec repli sur les autres coins si le contrôle
 *       qualité échoue.</li>
 *   <li>{@link Mode#NEEDS_TILING} — grand plan dont la passe 1 n'a pas su situer le cartouche :
 *       il faut basculer sur une approche par découpage en tuiles (à signaler avant de généraliser).</li>
 * </ul>
 *
 * @param mode                    chemin suivi
 * @param corner                  zone finalement retenue (nul en mode {@link Mode#SINGLE_PAGE})
 * @param extraction              paires libellé/valeur extraites (jamais nul ; vide si rien)
 * @param rawExtractionAnnotation JSON brut de l'annotation d'extraction (peut être nul)
 * @param rawLocationAnnotation   JSON brut de l'annotation de localisation, passe 1 (peut être nul)
 * @param qualityPassed           l'extraction retenue a passé le contrôle {@link CartouchePlausibility}
 * @param attempts                nombre de découpages évalués en passe 2 (tous en parallèle, pas séquentiels)
 */
public record CartoucheAnalysis(
        Mode mode,
        String corner,
        CartoucheExtraction extraction,
        JsonNode rawExtractionAnnotation,
        JsonNode rawLocationAnnotation,
        boolean qualityPassed,
        int attempts) {

    public enum Mode { SINGLE_PAGE, TWO_PASS_CROP, NEEDS_TILING }

    public static CartoucheAnalysis singlePage(CartoucheExtraction extraction, JsonNode rawExtraction) {
        return new CartoucheAnalysis(Mode.SINGLE_PAGE, null, extraction, rawExtraction, null, true, 1);
    }

    public static CartoucheAnalysis twoPassCrop(String corner, CartoucheExtraction extraction,
                                                JsonNode rawExtraction, JsonNode rawLocation,
                                                boolean qualityPassed, int attempts) {
        return new CartoucheAnalysis(Mode.TWO_PASS_CROP, corner, extraction, rawExtraction, rawLocation,
                qualityPassed, attempts);
    }

    public static CartoucheAnalysis needsTiling(String corner, JsonNode rawLocation) {
        return new CartoucheAnalysis(Mode.NEEDS_TILING, corner,
                new CartoucheExtraction(false, List.of()), null, rawLocation, false, 0);
    }
}
