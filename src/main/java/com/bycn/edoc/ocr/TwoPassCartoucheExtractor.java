package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrateur d'extraction du cartouche, robuste aux <b>grands formats</b>.
 *
 * <p>Constat : Mistral redimensionne toute image envoyée à une taille fixe. Sur un plan de 2&nbsp;m,
 * le cartouche (petit, dans un coin) devient illisible après ce redimensionnement — alors que sur un
 * A4, ou même un scan pur, la lecture pleine page fonctionne très bien.</p>
 *
 * <p>Le cœur prend un {@code byte[]} (le contenu du PDF), jamais un chemin de fichier : le smoke test
 * lit un fichier d'exemple et passe ses octets, l'API REST passera les octets d'un upload — la
 * logique ci-dessous est identique dans les deux cas. On envoie toujours à Mistral une <b>image
 * d'une seule page</b> (PNG rendu à partir de la première page), jamais le PDF entier : la limite de
 * 30 pages de l'API n'est donc jamais atteinte, quel que soit le nombre de pages du document.</p>
 *
 * <p>Stratégie (objectif : paires exactes, latence minimale, budget d'appels libre) :</p>
 * <ul>
 *   <li><b>Format standard</b> (≤ {@link #LARGE_PAGE_THRESHOLD_MM}) : rendu de la page entière,
 *       lecture directe (consensus multi-échantillons, voir {@link OcrConsensus}).</li>
 *   <li><b>Grand plan</b> : <b>une seule vague réseau</b>. Les quatre coins prioritaires sont rendus
 *       en parallèle (CPU) puis analysés <em>en même temps</em> (chacun en consensus). La
 *       localisation passe 1 ({@code locateEnabled}) part dans la même vague et ne sert qu'à
 *       <em>départager</em> : on ne fige jamais un coin par défaut. La sélection retient, parmi les
 *       coins qui passent le contrôle {@link CartouchePlausibility}, le plus « cartouche » au sens de
 *       {@link CartoucheScore} (codes courts d'identification, pas un panneau d'adresses).</li>
 *   <li>Si aucun coin ne passe le contrôle, on renvoie le coin le plus riche <b>non validé</b>
 *       (marqué à vérifier) plutôt que de perdre les données ; ce n'est que si <em>tous</em> les
 *       coins sont vides qu'on signale {@link CartoucheAnalysis.Mode#NEEDS_TILING}.</li>
 * </ul>
 *
 * <p>Leviers de latence (mesurés : le coût dominant est le temps d'OCR <em>par appel</em>, qui croît
 * avec le volume de texte de l'image envoyée) : {@code cornerFraction} (moins de surface = moins de
 * texte à lire), {@code cropLongPx} (résolution du découpage), {@code locateEnabled} (la passe 1 sur
 * un plan dense est un OCR complet du plan, souvent l'appel le plus lent de la vague).</p>
 */
public class TwoPassCartoucheExtractor {

    /** Au-delà de ce grand côté (mm), la page est un grand format → analyse par coins. */
    static final double LARGE_PAGE_THRESHOLD_MM = 430; // > A3 (long côté 420 mm)

    /** Largeur/hauteur de bande pour les zones centrées ou le long d'un bord (mode diagnostic). */
    static final double BAND_FRACTION = 0.70;

    /**
     * Résolution du rendu pleine page des formats <b>standard</b> (A4/A3), volontairement constante :
     * ces documents sont déjà rapides, on n'y touche pas quand on règle la latence des grands plans.
     */
    static final int SINGLE_PAGE_LONG_PX = 3400;

    /** Garde-fou mémoire : taille max du rendu pleine page intermédiaire (grand côté, en pixels). */
    static final int MAX_FULL_RENDER_PX = 7800;

    /**
     * Coins évalués (tous en parallèle). L'ordre sert uniquement de départage à score égal —
     * bas-droite d'abord (position la plus fréquente d'un cartouche), puis les autres. On ne
     * « suppose » aucun coin : chaque coin doit passer le contrôle qualité pour être retenu.
     */
    static final List<String> CORNER_PRIORITY = List.of("bottom-right", "bottom-left", "top-right", "top-left");

    private final MistralOcrClient client;
    /**
     * Diagnostic : si non vide, force la zone de découpage sur les grands plans, court-circuite la
     * passe 1 ET la sélection. Sert à isoler « le mécanisme de découpage marche-t-il ? » de « la
     * sélection est-elle correcte ? ». Vide en production (propriété {@code edoc.force-corner}).
     */
    private final String forcedCorner;
    /** Résolution visée du découpage de coin envoyé à Mistral (grand côté, px). */
    private final int cropLongPx;
    /** Résolution du rendu pleine page de la passe 1 (localisation grossière seulement). */
    private final int locateLongPx;
    /** Fraction de page prise depuis chaque coin (moins de surface = moins de texte = plus rapide). */
    private final double cornerFraction;
    /** Active la passe 1 (localisation). Elle ne sert qu'à départager le score entre coins. */
    private final boolean locateEnabled;

    public TwoPassCartoucheExtractor(MistralOcrClient client) {
        this(client, null);
    }

    public TwoPassCartoucheExtractor(MistralOcrClient client, String forcedCorner) {
        this(client, forcedCorner, 3400, 1400, 0.40, true);
    }

    public TwoPassCartoucheExtractor(MistralOcrClient client, String forcedCorner, int cropLongPx,
                                     int locateLongPx, double cornerFraction, boolean locateEnabled) {
        this.client = client;
        this.forcedCorner = (forcedCorner == null || forcedCorner.isBlank()) ? null : forcedCorner.trim();
        this.cropLongPx = cropLongPx;
        this.locateLongPx = locateLongPx;
        this.cornerFraction = cornerFraction;
        this.locateEnabled = locateEnabled;
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
            OcrResult result = client.analyzeImage(renderStandardPage(pdfBytes));
            return CartoucheAnalysis.singlePage(extractionOf(result), result.rawAnnotation());
        }

        // Mode diagnostic : une seule tentative sur la zone forcée, sans passe 1 ni sélection.
        if (forcedCorner != null) {
            OcrResult result = passTwo(pdfBytes, forcedCorner);
            CartoucheExtraction ex = extractionOf(result);
            return CartoucheAnalysis.twoPassCrop(forcedCorner, ex, result.rawAnnotation(), null,
                    CartouchePlausibility.looksLikeCartouche(ex), 1);
        }

        // UNE SEULE VAGUE. Rendus des coins en parallèle (CPU), puis analyse des quatre coins en même
        // temps (consensus par coin, mis en cache). La localisation part dans la même vague quand elle
        // est activée — sur un plan dense elle OCRise TOUT le plan, c'est l'appel le plus lourd : la
        // désactiver supprime ce coût sans changer la sélection (elle ne fait que départager).
        List<CompletableFuture<byte[]>> cropFutures = new ArrayList<>(CORNER_PRIORITY.size());
        for (String corner : CORNER_PRIORITY) {
            cropFutures.add(CompletableFuture.supplyAsync(() -> renderCorner(pdfBytes, corner)));
        }
        CompletableFuture<CartoucheLocation> locationFuture = locateEnabled
                ? CompletableFuture.supplyAsync(() -> renderLocatePage(pdfBytes))
                        .thenCompose(client::locateImageAsync)
                : CompletableFuture.completedFuture(new CartoucheLocation(false, "unknown", null));

        List<byte[]> crops = new ArrayList<>(cropFutures.size());
        for (CompletableFuture<byte[]> future : cropFutures) {
            crops.add(MistralOcrClient.joinUnwrapped(future));
        }
        List<OcrResult> results = MistralOcrClient.joinUnwrapped(client.analyzeImagesAsync(crops));
        CartoucheLocation location = MistralOcrClient.joinUnwrapped(locationFuture);

        boolean located = locateEnabled && !location.isUnknown() && location.cartoucheFound();
        // Ordre de départage : le coin suggéré par la passe 1 en tête (s'il fait partie des coins
        // évalués), sinon l'ordre de priorité brut.
        List<String> order = selectionOrder(located ? location.corner() : null);

        // Sélection : parmi les coins ÉLIGIBLES (contrôle qualité), le PLUS cartouche au sens de
        // CartoucheScore — pas le premier venu. Sur 20.pdf, le « panneau des intervenants »
        // (adresses/téléphones) passe le contrôle mais se classe sous le vrai cartouche (codes courts).
        // On ne fusionne ni ne filtre jamais de paires : on choisit un coin, verbatim.
        String chosenCorner = null;
        CartoucheExtraction chosenEx = null;
        JsonNode chosenRaw = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        String richestCorner = null;
        CartoucheExtraction richestEx = null;
        JsonNode richestRaw = null;

        for (String corner : order) {
            OcrResult result = results.get(CORNER_PRIORITY.indexOf(corner));
            CartoucheExtraction ex = extractionOf(result);
            if (CartouchePlausibility.looksLikeCartouche(ex)) {
                double score = CartoucheScore.score(ex);
                if (score > bestScore) {
                    bestScore = score;
                    chosenCorner = corner;
                    chosenEx = ex;
                    chosenRaw = result.rawAnnotation();
                }
            }
            if (richestEx == null || ex.fields().size() > richestEx.fields().size()) {
                richestEx = ex;
                richestRaw = result.rawAnnotation();
                richestCorner = corner;
            }
        }

        if (chosenEx != null) {
            return CartoucheAnalysis.twoPassCrop(chosenCorner, chosenEx, chosenRaw,
                    location.raw(), true, CORNER_PRIORITY.size());
        }
        // Aucun coin validé : on renvoie le plus riche, non validé (à vérifier), plutôt que de
        // perdre les données lues.
        if (richestEx != null && !richestEx.fields().isEmpty()) {
            return CartoucheAnalysis.twoPassCrop(richestCorner, richestEx, richestRaw,
                    location.raw(), false, CORNER_PRIORITY.size());
        }
        // Tous les coins sont vides : vraiment non localisable → découpage en tuiles à envisager.
        return CartoucheAnalysis.needsTiling(location.corner(), location.raw());
    }

    /**
     * Ordre de départage sur les coins <b>évalués</b> ({@link #CORNER_PRIORITY}) : le coin préféré
     * (suggéré par la passe 1) en tête s'il en fait partie, sinon l'ordre de priorité brut. Contrairement
     * à {@link #candidateOrder}, ne renvoie jamais un coin hors des coins évalués (ex. « top-center »).
     */
    static List<String> selectionOrder(String preferred) {
        String canonical = preferred == null ? null : preferred.trim().toLowerCase();
        if (canonical == null || !CORNER_PRIORITY.contains(canonical)) {
            return CORNER_PRIORITY;
        }
        List<String> ordered = new ArrayList<>();
        ordered.add(canonical);
        for (String corner : CORNER_PRIORITY) {
            if (!corner.equals(canonical)) {
                ordered.add(corner);
            }
        }
        return ordered;
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
        return client.analyzeImage(renderCorner(pdfBytes, corner));
    }

    /** Rend le découpage plein résolution d'un coin (fraction et résolution configurables). */
    private byte[] renderCorner(byte[] pdfBytes, String corner) {
        CropRegion region = CropRegion.forCorner(corner, cornerFraction, BAND_FRACTION);
        try {
            return PdfSupport.renderRegionPng(pdfBytes, 0, region, cropLongPx, MAX_FULL_RENDER_PX);
        } catch (IOException e) {
            throw new MistralOcrException("Rendu du découpage cartouche impossible : " + e.getMessage(), e);
        }
    }

    /** Rend la première page entière pour la lecture directe des formats standard (résolution fixe). */
    private byte[] renderStandardPage(byte[] pdfBytes) {
        try {
            return PdfSupport.renderFirstPagePng(pdfBytes, SINGLE_PAGE_LONG_PX, MAX_FULL_RENDER_PX);
        } catch (IOException e) {
            throw new MistralOcrException("Rendu de la page en image impossible : " + e.getMessage(), e);
        }
    }

    /** Rend la première page entière pour la passe 1 (résolution réduite : zone grossière seulement). */
    private byte[] renderLocatePage(byte[] pdfBytes) {
        try {
            return PdfSupport.renderFirstPagePng(pdfBytes, locateLongPx, MAX_FULL_RENDER_PX);
        } catch (IOException e) {
            throw new MistralOcrException("Rendu de la page en image impossible : " + e.getMessage(), e);
        }
    }

    private static CartoucheExtraction extractionOf(OcrResult result) {
        return result.hasAnnotation() ? result.extraction() : new CartoucheExtraction(false, List.of());
    }
}
