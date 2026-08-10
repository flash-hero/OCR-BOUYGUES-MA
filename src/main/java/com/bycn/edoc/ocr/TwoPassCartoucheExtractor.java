package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrateur d'extraction du cartouche : <b>localiser d'abord, lire ensuite</b>.
 *
 * <p>Constat : un modèle redimensionne toute image envoyée. Sur un plan de 2&nbsp;m, le cartouche
 * (petit, quelque part le long d'un bord) devient illisible après ce redimensionnement — et plus
 * l'image contient de texte, moins la transcription est fidèle (mesuré : élargir le découpage a
 * dégradé l'appariement libellé/valeur et tronqué des valeurs). La stratégie découle de ces deux
 * mesures : envoyer au modèle la <b>plus petite image possible contenant tout le cartouche</b>.</p>
 *
 * <p>Le cœur prend un {@code byte[]} (le contenu du PDF), jamais un chemin de fichier : l'API REST
 * passe les octets d'un upload, et la logique d'extraction ne sait pas d'où ils viennent. Quand une
 * image est nécessaire, c'est toujours celle d'une <b>seule page</b> (rendue par nous), jamais le
 * PDF entier.</p>
 *
 * <p>Stratégie (objectif : paires exactes, moins de 20&nbsp;s par document) :</p>
 * <ol>
 *   <li><b>Passe 1 — localisation</b> ({@code locateEnabled}, tous formats) : la page (réduite à la
 *       zone encrée, voir {@link PageInkBounds}) part en basse résolution ; le modèle renvoie la
 *       <b>boîte englobante</b> du cartouche, sa zone, et la rotation à appliquer pour que son texte
 *       soit droit. On ne demande aucun contenu : une position survit au redimensionnement, pas le
 *       texte fin. Aucune position n'est jamais présumée — c'est le modèle qui situe la boîte,
 *       document par document.</li>
 *   <li><b>Passe 2 — lecture ciblée</b> : la boîte (avec une petite marge) est re-rendue en haute
 *       résolution depuis le PDF vectoriel, redressée si besoin, et lue en un seul appel. Le
 *       contrôle {@link CartouchePlausibility} valide le résultat.</li>
 *   <li><b>Repli</b> — localisation absente, boîte inexploitable ou lecture invraisemblable :
 *       format standard → lecture pleine page (chemin historique) ; grand plan → vague des quatre
 *       coins en parallèle, départagés par {@link CartoucheScore} (chemin historique). Si aucun coin
 *       ne passe le contrôle, le plus riche part <b>non validé</b> ; si tous sont vides,
 *       {@link CartoucheAnalysis.Mode#NEEDS_TILING}.</li>
 * </ol>
 */
public class TwoPassCartoucheExtractor {

    private static final Logger log = LoggerFactory.getLogger(TwoPassCartoucheExtractor.class);

    /** Au-delà de ce grand côté (mm), la page est un grand format → repli par coins (jamais pleine page). */
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
     * Marge ajoutée autour de la boîte localisée (fraction de page, plancher) : absorbe une boîte
     * légèrement trop serrée sans ré-élargir l'image — le mécanisme mesuré de dégradation.
     */
    static final double BOX_MARGIN_MIN = 0.02;

    /**
     * Marge relative à la taille de la boîte (la plus grande des deux marges s'applique). Faible à
     * dessein : la marge protège d'une boîte trop serrée, mais chaque pour-cent ajouté ramène du
     * texte voisin — et le temps de lecture est proportionnel au nombre de paires transcrites
     * (mesuré : 25-30 paires = 14 à 20 s d'appel, 10-14 paires = 4 à 7 s).
     */
    static final double BOX_MARGIN_RELATIVE = 0.05;

    /**
     * Aire maximale (fraction de page) d'une boîte localisée sur un <b>grand plan</b>. Au-delà, la
     * « boîte » couvrirait une part du plan telle que l'image ciblée retombe dans le cas mesuré
     * « trop de texte, transcription infidèle » — mieux vaut le repli par coins, calibré pour ça.
     */
    static final double LARGE_MAX_BOX_AREA = 0.35;

    /** Aire maximale d'une boîte sur un format standard (au-delà : pleine page, strictement équivalent). */
    static final double STANDARD_MAX_BOX_AREA = 0.70;

    /**
     * Coins évalués par le <b>repli</b> (tous en parallèle). L'ordre sert uniquement de départage à
     * score égal — bas-droite d'abord (position la plus fréquente), puis les autres. On ne
     * « suppose » aucun coin : chaque coin doit passer le contrôle qualité pour être retenu.
     */
    static final List<String> CORNER_PRIORITY = List.of("bottom-right", "bottom-left", "top-right", "top-left");

    /**
     * Seuil de la question de tri « ce bloc porte-t-il un des libellés demandés ? ». Bas à dessein
     * (voir {@link #mentionsAnyTarget}) : on ne cherche pas à classer, seulement à repérer le bloc
     * qui ne parle manifestement pas du bon sujet.
     */
    static final int LABEL_HINT_THRESHOLD = 85;

    private final OcrClient client;
    /**
     * Diagnostic : si non vide, force la zone de découpage sur les grands plans, court-circuite la
     * localisation ET la sélection. Sert à isoler « le mécanisme de découpage marche-t-il ? » de
     * « la localisation est-elle correcte ? ». Vide en production (propriété {@code edoc.force-corner}).
     */
    private final String forcedCorner;
    /** Résolution visée du découpage envoyé au modèle (grand côté, px). */
    private final int cropLongPx;
    /** Résolution du rendu pleine page de la passe 1 (localisation seulement). */
    private final int locateLongPx;
    /** Fraction de page prise depuis chaque coin par le repli (moins de surface = moins de texte). */
    private final double cornerFraction;
    /** Active la passe 1 (localisation + lecture ciblée). Sinon : chemins de repli directement. */
    private final boolean locateEnabled;
    /** Prend découpages et localisation sur la zone réellement dessinée (voir {@link PageInkBounds}). */
    private final boolean inkBoundsEnabled;
    /** Lit d'abord la couche texte du PDF (voir {@link PdfTextLayer}) ; l'image n'est qu'un repli. */
    private final boolean textLayerEnabled;

    public TwoPassCartoucheExtractor(OcrClient client) {
        this(client, null);
    }

    public TwoPassCartoucheExtractor(OcrClient client, String forcedCorner) {
        this(client, forcedCorner, 3400, 1400, 0.40, true, true);
    }

    public TwoPassCartoucheExtractor(OcrClient client, String forcedCorner, int cropLongPx,
                                     int locateLongPx, double cornerFraction, boolean locateEnabled,
                                     boolean inkBoundsEnabled) {
        this(client, forcedCorner, cropLongPx, locateLongPx, cornerFraction, locateEnabled,
                inkBoundsEnabled, true);
    }

    public TwoPassCartoucheExtractor(OcrClient client, String forcedCorner, int cropLongPx,
                                     int locateLongPx, double cornerFraction, boolean locateEnabled,
                                     boolean inkBoundsEnabled, boolean textLayerEnabled) {
        this.textLayerEnabled = textLayerEnabled;
        this.client = client;
        this.forcedCorner = (forcedCorner == null || forcedCorner.isBlank()) ? null : forcedCorner.trim();
        this.cropLongPx = cropLongPx;
        this.locateLongPx = locateLongPx;
        this.cornerFraction = cornerFraction;
        this.locateEnabled = locateEnabled;
        this.inkBoundsEnabled = inkBoundsEnabled;
    }

    /** Extraction générique seule : aucune proposition de rattachement demandée. */
    public CartoucheAnalysis extract(byte[] pdfBytes) {
        return extract(pdfBytes, List.of());
    }

    /**
     * Extraction + proposition de rattachement aux champs cibles de l'appel (voir {@link ReadTarget}).
     * La lecture reste complète et générique : les cibles ajoutent une proposition, jamais un filtre.
     */
    public CartoucheAnalysis extract(byte[] pdfBytes, List<ReadTarget> targets) {
        List<ReadTarget> safeTargets = (targets == null) ? List.of() : targets;
        double longMm;
        try {
            longMm = PdfSupport.pageLongSideMm(pdfBytes, 0);
        } catch (IOException e) {
            throw new OcrException("Lecture des dimensions du PDF impossible : " + e.getMessage(), e);
        }
        boolean large = longMm > LARGE_PAGE_THRESHOLD_MM;

        // VOIE PRINCIPALE : le texte déjà contenu dans le PDF. Un plan de CAO n'est pas une photo —
        // 27 des 28 documents du corpus portent leur texte, exact, avec sa mise en page. Le faire
        // relire par un modèle de vision, c'est le transcrire une seconde fois, plus lentement et
        // avec des erreurs que la première transcription n'avait pas. Le modèle ne sert alors qu'à
        // ce qu'il fait le mieux : reconnaître le cartouche au milieu du reste.
        if (textLayerEnabled) {
            String text = PdfTextLayer.of(pdfBytes, 0);
            if (!text.isEmpty()) {
                long start = System.nanoTime();
                OcrResult result = client.analyzeText(text, safeTargets);
                CartoucheExtraction ex = extractionOf(result);
                boolean usable = CartouchePlausibility.looksLikeCartouche(ex)
                        && mentionsAnyTarget(ex, safeTargets);
                log.info("Couche texte : {} caractères, {} paires en {} ms{}",
                        text.length(), ex.fields().size(), (System.nanoTime() - start) / 1_000_000,
                        usable ? "" : " — inexploitable, repli sur l'image");
                if (usable) {
                    return CartoucheAnalysis.textLayer(ex, result.rawAnnotation());
                }
            } else {
                log.info("Aucune couche texte exploitable : lecture par l'image");
            }
        }

        // Mode diagnostic : une seule tentative sur la zone forcée, sans localisation ni sélection.
        if (large && forcedCorner != null) {
            CropRegion ink = inkBoundsEnabled ? PageInkBounds.of(pdfBytes, 0) : PdfSupport.FULL_PAGE;
            byte[] crop = renderRegion(pdfBytes,
                    CropRegion.forCorner(forcedCorner, cornerFraction, BAND_FRACTION).within(ink));
            OcrResult result = client.analyzeImage(crop, safeTargets);
            CartoucheExtraction ex = extractionOf(result);
            return CartoucheAnalysis.twoPassCrop(forcedCorner, ex, result.rawAnnotation(), null,
                    CartouchePlausibility.looksLikeCartouche(ex), 1);
        }

        CropRegion ink = PdfSupport.FULL_PAGE;
        CartoucheLocation location = null;
        CartoucheAnalysis boxedCandidate = null;
        if (locateEnabled) {
            // UN SEUL rendu basse résolution sert trois usages : la boîte d'encre (zone réellement
            // dessinée — une feuille à moitié vide fausserait tous les découpages, cas 13.pdf), le
            // rognage des marges, et l'image de localisation elle-même. Peindre un A0 dense coûte
            // plusieurs secondes quel que soit le DPI : chaque rendu évité compte. L'image part en
            // JPEG : la localisation ne lit aucun texte, et le PNG sans perte d'un plan dense pèse
            // plusieurs Mo qui partent en base64 dans la requête.
            long prepStart = System.nanoTime();
            byte[] locateImage;
            try {
                java.awt.image.BufferedImage page = PdfSupport.renderRegionDirect(
                        pdfBytes, 0, PdfSupport.FULL_PAGE, locateLongPx, MAX_FULL_RENDER_PX, true);
                if (inkBoundsEnabled) {
                    ink = PageInkBounds.probe(page);
                }
                locateImage = PdfSupport.toJpegBytes(ink.isFullPage() ? page : PdfSupport.crop(page, ink));
            } catch (IOException e) {
                throw new OcrException("Rendu de la page en image impossible : " + e.getMessage(), e);
            }
            long prepMs = (System.nanoTime() - prepStart) / 1_000_000;
            debugDump("locate", locateImage);
            long locateStart = System.nanoTime();
            location = client.locateImage(locateImage, safeTargets);
            long locateMs = (System.nanoTime() - locateStart) / 1_000_000;
            log.info("Préparation localisation {} ms (rendu {} px + encre + jpeg {} Ko)",
                    prepMs, locateLongPx, locateImage.length / 1024);
            CropRegion region = targetRegion(location, ink, large);
            if (region != null) {
                // Passe 2 ciblée : la plus petite image contenant tout le cartouche — seule cette
                // zone est rastérisée (rendre un A0 entier en haute résolution coûte des dizaines
                // de secondes, rendre sa seule zone cartouche en coûte une fraction).
                long readStart = System.nanoTime();
                byte[] crop = ImageOps.rotate(renderRegion(pdfBytes, region), location.rotation());
                debugDump("boxed", crop);
                OcrResult result = client.analyzeImage(crop, safeTargets);
                long readMs = (System.nanoTime() - readStart) / 1_000_000;
                CartoucheExtraction ex = extractionOf(result);
                log.info("Localisation {} ms (zone {}, boîte x={} y={} w={} h={}, rotation {}), "
                                + "lecture ciblée {} ms ({} paires)",
                        locateMs, zoneOf(location, region),
                        round2(region.x()), round2(region.y()), round2(region.w()), round2(region.h()),
                        location.rotation(), readMs, ex.fields().size());
                CartoucheAnalysis boxed = CartoucheAnalysis.twoPassCrop(zoneOf(location, region), ex,
                        result.rawAnnotation(), location.raw(),
                        CartouchePlausibility.looksLikeCartouche(ex), 1);
                // Le contrôle générique ne suffit pas quand l'appelant a dit ce qu'il cherche :
                // l'annuaire des intervenants est un formulaire parfaitement plausible, mais il ne
                // porte AUCUN des libellés demandés. Zéro champ reconnu = boîte suspecte, on laisse
                // le repli trancher (rare, donc sans coût sur le cas nominal).
                if (boxed.qualityPassed() && mentionsAnyTarget(ex, safeTargets)) {
                    return boxed;
                }
                boxedCandidate = boxed;
            } else {
                log.info("Localisation {} ms sans boîte exploitable (zone {}) : repli", locateMs, location.corner());
            }
        } else if (inkBoundsEnabled) {
            // Localisation coupée : la boîte d'encre garde son propre sondage (chemin historique).
            ink = PageInkBounds.of(pdfBytes, 0);
        }

        return large
                ? cornerSweep(pdfBytes, ink, location, boxedCandidate, safeTargets)
                : singlePage(pdfBytes, boxedCandidate, safeTargets);
    }

    /**
     * Repli des formats standard : lecture pleine page directe (chemin historique, éprouvé). Si la
     * lecture ciblée écartée avait lu davantage, c'est elle qu'on garde — on ne perd jamais ce qui a
     * déjà été lu, même quand le repli a été jugé nécessaire.
     */
    private CartoucheAnalysis singlePage(byte[] pdfBytes, CartoucheAnalysis boxedCandidate,
                                         List<ReadTarget> targets) {
        OcrResult result = client.analyzeImage(renderStandardPage(pdfBytes), targets);
        CartoucheExtraction ex = extractionOf(result);
        if (boxedCandidate != null && boxedCandidate.extraction().fields().size() > ex.fields().size()) {
            return boxedCandidate;
        }
        return CartoucheAnalysis.singlePage(ex, result.rawAnnotation());
    }

    /**
     * Repli des grands plans : les quatre coins en une seule vague concurrente (consensus par coin),
     * départagés par {@link CartoucheScore} parmi ceux qui passent le contrôle qualité — jamais le
     * premier venu. La zone suggérée par la localisation, si elle existe, passe simplement en tête
     * de l'ordre de départage à score égal.
     */
    private CartoucheAnalysis cornerSweep(byte[] pdfBytes, CropRegion ink, CartoucheLocation location,
                                          CartoucheAnalysis boxedCandidate, List<ReadTarget> targets) {
        List<CompletableFuture<byte[]>> cropFutures = new ArrayList<>(CORNER_PRIORITY.size());
        for (String corner : CORNER_PRIORITY) {
            cropFutures.add(CompletableFuture.supplyAsync(() ->
                    renderRegion(pdfBytes, CropRegion.forCorner(corner, cornerFraction, BAND_FRACTION).within(ink))));
        }
        List<byte[]> crops = new ArrayList<>(cropFutures.size());
        for (CompletableFuture<byte[]> future : cropFutures) {
            crops.add(OcrClient.joinUnwrapped(future));
        }
        List<OcrResult> results = OcrClient.joinUnwrapped(client.analyzeImagesAsync(crops, targets));

        boolean located = location != null && !location.isUnknown() && location.cartoucheFound();
        List<String> order = selectionOrder(located ? location.corner() : null);

        String chosenCorner = null;
        CartoucheExtraction chosenEx = null;
        JsonNode chosenRaw = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        String richestCorner = null;
        CartoucheExtraction richestEx = null;
        JsonNode richestRaw = null;

        // Un coin qui porte un des libellés demandés passe AVANT un coin qui n'en porte aucun, quel
        // que soit son score : sur 22.pdf, l'annuaire des intervenants est plus riche et plus
        // « plausible » que le vrai cartouche, mais il ne parle pas du même sujet. À égalité sur ce
        // critère, le score de contenu départage comme avant.
        boolean bestMentions = false;
        boolean richestMentions = false;

        for (String corner : order) {
            OcrResult result = results.get(CORNER_PRIORITY.indexOf(corner));
            CartoucheExtraction ex = extractionOf(result);
            boolean mentions = mentionsAnyTarget(ex, targets);
            if (CartouchePlausibility.looksLikeCartouche(ex)) {
                double score = CartoucheScore.score(ex);
                if (preferable(mentions, score, bestMentions, bestScore, chosenEx == null)) {
                    bestMentions = mentions;
                    bestScore = score;
                    chosenCorner = corner;
                    chosenEx = ex;
                    chosenRaw = result.rawAnnotation();
                }
            }
            if (preferable(mentions, ex.fields().size(), richestMentions,
                    richestEx == null ? -1 : richestEx.fields().size(), richestEx == null)) {
                richestMentions = mentions;
                richestEx = ex;
                richestRaw = result.rawAnnotation();
                richestCorner = corner;
            }
        }

        JsonNode rawLocation = location == null ? null : location.raw();
        if (chosenEx != null) {
            return CartoucheAnalysis.twoPassCrop(chosenCorner, chosenEx, chosenRaw,
                    rawLocation, true, CORNER_PRIORITY.size());
        }
        // Aucun coin validé : le plus riche part non validé (à vérifier) plutôt que de perdre les
        // données lues — en comptant la lecture ciblée invraisemblable parmi les candidats.
        // La lecture ciblée écartée reste dans la course : elle est jugée sur les mêmes deux
        // critères que les coins — parler du bon sujet d'abord, richesse ensuite.
        if (boxedCandidate != null && preferable(
                mentionsAnyTarget(boxedCandidate.extraction(), targets),
                boxedCandidate.extraction().fields().size(),
                richestMentions,
                richestEx == null ? -1 : richestEx.fields().size(),
                richestEx == null)) {
            return boxedCandidate;
        }
        if (richestEx != null && !richestEx.fields().isEmpty()) {
            return CartoucheAnalysis.twoPassCrop(richestCorner, richestEx, richestRaw,
                    rawLocation, false, CORNER_PRIORITY.size());
        }
        // Tous les coins sont vides : vraiment non localisable → découpage en tuiles à envisager.
        return CartoucheAnalysis.needsTiling(location == null ? "unknown" : location.corner(), rawLocation);
    }

    /**
     * La zone de lecture ciblée déduite de la localisation, en fractions de <b>page</b>, ou
     * {@code null} si la localisation ne fournit pas de boîte exploitable : absente, dégénérée, ou
     * trop grande pour rester dans le régime mesuré « peu de texte, transcription fidèle ».
     */
    private static CropRegion targetRegion(CartoucheLocation location, CropRegion ink, boolean large) {
        if (location == null || !location.hasBox()) {
            return null;
        }
        // La boîte est en fractions de l'image envoyée (la zone encrée) → retour en fractions de page,
        // puis marge : la plus grande entre le plancher et la marge relative à la taille de la boîte.
        CropRegion onPage = location.box().within(ink);
        double margin = Math.max(BOX_MARGIN_MIN, BOX_MARGIN_RELATIVE * Math.max(onPage.w(), onPage.h()));
        CropRegion padded = onPage.expanded(margin);
        double maxArea = large ? LARGE_MAX_BOX_AREA : STANDARD_MAX_BOX_AREA;
        if (padded.w() * padded.h() > maxArea) {
            return null;
        }
        return padded;
    }

    /**
     * Un candidat en remplace-t-il un autre ? Porter un libellé demandé prime sur tout ; à égalité
     * sur ce point, la note la plus haute gagne. {@code first} accepte d'office le premier candidat.
     */
    private static boolean preferable(boolean mentions, double value,
                                      boolean bestMentions, double bestValue, boolean first) {
        if (first) {
            return true;
        }
        if (mentions != bestMentions) {
            return mentions;
        }
        return value > bestValue;
    }

    /**
     * L'extraction porte-t-elle au moins un des champs demandés — soit rattaché par le modèle, soit
     * reconnaissable à son libellé ? Sans champs cibles, la question ne se pose pas et
     * la réponse est {@code true} : le contrôle générique reste alors seul juge.
     *
     * <p>Volontairement <b>grossier et permissif</b> : ce n'est pas la classification (qui vit dans
     * sa propre couche, au-dessus de celle-ci, avec son seuil calibrable) mais une simple question
     * de tri — « ce bloc parle-t-il seulement du même sujet ? ». Un seul libellé reconnu suffit à
     * garder la lecture ; c'est le cas <em>zéro</em> qui déclenche le repli.</p>
     */
    private static boolean mentionsAnyTarget(CartoucheExtraction extraction, List<ReadTarget> targets) {
        if (targets.isEmpty() || !extraction.assignments().isEmpty()) {
            return true;
        }
        for (ReadTarget target : targets) {
            for (String label : target.labels()) {
                String expected = simplify(label);
                if (expected.isEmpty()) {
                    continue;
                }
                for (CartoucheField pair : extraction.fields()) {
                    String printed = simplify(pair.label());
                    if (!printed.isEmpty()
                            && FuzzySearch.tokenSetRatio(printed, expected) >= LABEL_HINT_THRESHOLD) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Repli des accents, minuscules, espaces compactés — assez pour une comparaison de tri. */
    private static String simplify(String raw) {
        if (raw == null) {
            return "";
        }
        return java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Zone (grille 3×3) publiée dans le résultat : celle du modèle si valide, sinon dérivée de la boîte. */
    private static String zoneOf(CartoucheLocation location, CropRegion region) {
        String corner = location.corner();
        if (corner != null && !corner.isBlank() && !corner.trim().equalsIgnoreCase("unknown")) {
            return corner.trim().toLowerCase();
        }
        return region.zoneLabel();
    }

    /**
     * Ordre de départage sur les coins <b>évalués</b> ({@link #CORNER_PRIORITY}) : le coin préféré
     * (suggéré par la localisation) en tête s'il en fait partie, sinon l'ordre de priorité brut.
     * Contrairement à {@link #candidateOrder}, ne renvoie jamais un coin hors des coins évalués.
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

    /**
     * Rend une zone de page en PNG haute résolution (grand côté ≈ {@code cropLongPx}) en ne
     * rastérisant que cette zone — voir {@link PdfSupport#renderRegionDirectPng}.
     */
    private byte[] renderRegion(byte[] pdfBytes, CropRegion region) {
        try {
            return PdfSupport.renderRegionDirectPng(pdfBytes, 0, region, cropLongPx, MAX_FULL_RENDER_PX);
        } catch (IOException e) {
            throw new OcrException("Rendu du découpage cartouche impossible : " + e.getMessage(), e);
        }
    }

    /** Rend la première page entière pour la lecture directe des formats standard (résolution fixe). */
    private byte[] renderStandardPage(byte[] pdfBytes) {
        try {
            return PdfSupport.renderRegionDirectPng(pdfBytes, 0, PdfSupport.FULL_PAGE,
                    SINGLE_PAGE_LONG_PX, MAX_FULL_RENDER_PX);
        } catch (IOException e) {
            throw new OcrException("Rendu de la page en image impossible : " + e.getMessage(), e);
        }
    }

    /** Rend la zone encrée en basse résolution pour la localisation (position, pas lecture). */
    private byte[] renderLocateImage(byte[] pdfBytes, CropRegion ink) {
        try {
            return PdfSupport.renderRegionDirectPng(pdfBytes, 0, ink, locateLongPx, MAX_FULL_RENDER_PX);
        } catch (IOException e) {
            throw new OcrException("Rendu de la page en image impossible : " + e.getMessage(), e);
        }
    }

    private static CartoucheExtraction extractionOf(OcrResult result) {
        return result.hasAnnotation() ? result.extraction() : new CartoucheExtraction(false, List.of());
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /**
     * Écrit une image envoyée au modèle sur disque, pour vérification à l'œil — uniquement si la
     * propriété {@code edoc.debug-dump-dir} est renseignée (jamais en production). Règle du projet :
     * avant de conclure « le modèle n'a pas su lire », regarder l'image qu'on lui a réellement envoyée.
     */
    private static void debugDump(String stage, byte[] png) {
        String dir = System.getProperty("edoc.debug-dump-dir", "");
        if (dir.isBlank()) {
            return;
        }
        try {
            java.nio.file.Path out = java.nio.file.Path.of(dir);
            java.nio.file.Files.createDirectories(out);
            java.nio.file.Files.write(out.resolve(System.currentTimeMillis() + "-" + stage + ".png"), png);
        } catch (IOException e) {
            log.warn("Écriture du dump de diagnostic impossible : {}", e.getMessage());
        }
    }
}
