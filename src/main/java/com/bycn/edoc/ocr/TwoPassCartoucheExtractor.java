package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrateur d'extraction du cartouche, robuste aux <b>grands formats</b>.
 *
 * <p>Constat : Mistral redimensionne toute image envoyée à une taille fixe. Sur un plan de 2&nbsp;m,
 * le cartouche (petit, dans un coin) devient illisible après ce redimensionnement — alors que sur un
 * A4, ou même un scan pur, la lecture pleine page fonctionne très bien.</p>
 *
 * <p>Stratégie :</p>
 * <p>Le cœur prend un {@code byte[]} (le contenu du PDF), jamais un chemin de fichier : le smoke test
 * lit un fichier d'exemple et passe ses octets, l'API REST passera les octets d'un upload — la
 * logique ci-dessous est identique dans les deux cas. On envoie toujours à Mistral une <b>image
 * d'une seule page</b> (PNG rendu à partir de la première page), jamais le PDF entier : la limite de
 * 30 pages de l'API n'est donc jamais atteinte, quel que soit le nombre de pages du document.</p>
 *
 * <ul>
 *   <li><b>Format standard</b> (≤ {@link #LARGE_PAGE_THRESHOLD_MM}) : rendu de la page entière, lecture directe.</li>
 *   <li><b>Grand plan</b> : passe 1 = localisation grossière (zone), passe 2 = rendu plein
 *       résolution d'un découpage <em>généreux</em> autour de la zone, extraction, puis
 *       <b>contrôle qualité</b> ({@link CartouchePlausibility}). Si le contrôle échoue — la passe 1
 *       s'est trompée, typiquement en visant le titre — on <b>replie</b> sur les autres coins, un à
 *       un, dans l'ordre, en s'arrêtant au premier qui passe le contrôle. On ne fige jamais un coin
 *       par défaut : le repli n'accepte un coin que s'il produit un vrai cartouche.</li>
 *   <li>Si la passe 1 ne sait pas situer le cartouche ({@code unknown}), on le signale
 *       ({@link CartoucheAnalysis.Mode#NEEDS_TILING}).</li>
 * </ul>
 */
public class TwoPassCartoucheExtractor {

    /** Au-delà de ce grand côté (mm), la page est un grand format → lecture en deux passes. */
    static final double LARGE_PAGE_THRESHOLD_MM = 430; // > A3 (long côté 420 mm)

    /** Zone carrée prise depuis le coin identifié (généreuse pour absorber l'imprécision passe 1). */
    static final double CORNER_FRACTION = 0.40;

    /** Largeur/hauteur de bande pour les cartouches centrés ou le long d'un bord. */
    static final double BAND_FRACTION = 0.70;

    /**
     * Résolution visée du découpage envoyé en passe 2 (grand côté, en pixels). Plus la valeur est
     * haute, plus les petits libellés du cartouche restent lisibles après le redimensionnement
     * interne de Mistral (moins de libellés brouillés type ÉMETTEUR→DIRETTEUR).
     */
    static final int TARGET_CROP_LONG_PX = 3400;

    /** Garde-fou mémoire : taille max du rendu pleine page (grand côté, en pixels). */
    static final int MAX_FULL_RENDER_PX = 7800;

    /**
     * Ordre de repli sur les coins quand la passe 1 s'est trompée. Bas-droite d'abord (position la
     * plus fréquente d'un cartouche), puis les autres. On garde les bords/bandes en dernier recours,
     * non implémenté ici. On ne « suppose » aucun coin : chaque essai doit passer le contrôle qualité.
     */
    static final List<String> CORNER_PRIORITY = List.of("bottom-right", "bottom-left", "top-right", "top-left");

    private final MistralOcrClient client;
    /**
     * Diagnostic : si non vide, force la zone de découpage sur les grands plans, court-circuite la
     * passe 1 ET le repli. Sert à isoler « le mécanisme de découpage marche-t-il ? » de « la
     * localisation est-elle correcte ? ». Vide en production (propriété {@code edoc.force-corner}).
     */
    private final String forcedCorner;

    public TwoPassCartoucheExtractor(MistralOcrClient client) {
        this(client, null);
    }

    public TwoPassCartoucheExtractor(MistralOcrClient client, String forcedCorner) {
        this.client = client;
        this.forcedCorner = (forcedCorner == null || forcedCorner.isBlank()) ? null : forcedCorner.trim();
    }

    public CartoucheAnalysis extract(byte[] pdfBytes) {
        double longMm;
        try {
            longMm = PdfSupport.pageLongSideMm(pdfBytes, 0);
        } catch (IOException e) {
            throw new MistralOcrException("Lecture des dimensions du PDF impossible : " + e.getMessage(), e);
        }

        if (longMm <= LARGE_PAGE_THRESHOLD_MM) {
            // Format standard : on rend la page entière en image et on lit directement.
            OcrResult result = client.analyzeImage(renderFullPage(pdfBytes));
            return CartoucheAnalysis.singlePage(extractionOf(result), result.rawAnnotation());
        }

        // Mode diagnostic : une seule tentative sur la zone forcée, sans passe 1 ni repli.
        if (forcedCorner != null) {
            OcrResult result = passTwo(pdfBytes, forcedCorner);
            CartoucheExtraction ex = extractionOf(result);
            return CartoucheAnalysis.twoPassCrop(forcedCorner, ex, result.rawAnnotation(), null,
                    CartouchePlausibility.looksLikeCartouche(ex), 1);
        }

        // Passe 1 : localisation grossière sur l'image pleine page (vise la boîte-formulaire, pas le titre).
        CartoucheLocation location = client.locateImage(renderFullPage(pdfBytes));
        if (location.isUnknown() || !location.cartoucheFound()) {
            return CartoucheAnalysis.needsTiling(location.corner(), location.raw());
        }

        // Passe 2 + contrôle qualité, avec repli ordonné sur les autres coins si besoin.
        List<String> candidates = candidateOrder(location.corner());
        CartoucheExtraction best = null;
        JsonNode bestRawAnnotation = null;
        String bestCorner = location.corner();
        int attempts = 0;

        for (String corner : candidates) {
            attempts++;
            OcrResult result = passTwo(pdfBytes, corner);
            CartoucheExtraction ex = extractionOf(result);
            if (CartouchePlausibility.looksLikeCartouche(ex)) {
                return CartoucheAnalysis.twoPassCrop(corner, ex, result.rawAnnotation(),
                        location.raw(), true, attempts);
            }
            if (best == null || ex.fields().size() > best.fields().size()) {
                best = ex;
                bestRawAnnotation = result.rawAnnotation();
                bestCorner = corner;
            }
        }

        // Aucun coin n'a passé le contrôle qualité : on renvoie le meilleur essai, non validé.
        return CartoucheAnalysis.twoPassCrop(bestCorner, best, bestRawAnnotation,
                location.raw(), false, attempts);
    }

    /** Zone de la passe 1 en premier, puis les autres coins dans l'ordre de priorité. */
    static List<String> candidateOrder(String zone) {
        List<String> ordered = new ArrayList<>();
        ordered.add(zone);
        for (String corner : CORNER_PRIORITY) {
            if (!corner.equalsIgnoreCase(zone)) {
                ordered.add(corner);
            }
        }
        return ordered;
    }

    private OcrResult passTwo(byte[] pdfBytes, String corner) {
        CropRegion region = CropRegion.forCorner(corner, CORNER_FRACTION, BAND_FRACTION);
        byte[] png;
        try {
            png = PdfSupport.renderRegionPng(pdfBytes, 0, region, TARGET_CROP_LONG_PX, MAX_FULL_RENDER_PX);
        } catch (IOException e) {
            throw new MistralOcrException("Rendu du découpage cartouche impossible : " + e.getMessage(), e);
        }
        return client.analyzeImage(png);
    }

    /** Rend la première page entière en PNG (même mécanisme et mêmes bornes que la passe 2). */
    private byte[] renderFullPage(byte[] pdfBytes) {
        try {
            return PdfSupport.renderFirstPagePng(pdfBytes, TARGET_CROP_LONG_PX, MAX_FULL_RENDER_PX);
        } catch (IOException e) {
            throw new MistralOcrException("Rendu de la page en image impossible : " + e.getMessage(), e);
        }
    }

    private static CartoucheExtraction extractionOf(OcrResult result) {
        return result.hasAnnotation() ? result.extraction() : new CartoucheExtraction(false, List.of());
    }
}
