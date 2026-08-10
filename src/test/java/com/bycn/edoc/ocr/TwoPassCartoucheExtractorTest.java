package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/**
 * Vérifie l'orchestration « localiser d'abord, lire ensuite » et ses replis, sur des octets
 * uniquement (jamais de chemin de fichier) : localisation → lecture ciblée de la boîte quand elle
 * est exploitable, sinon pleine page (format standard) ou vague des quatre coins (grand plan).
 */
class TwoPassCartoucheExtractorTest {

    private static final CropRegion BOTTOM_RIGHT_BOX = new CropRegion(0.62, 0.66, 0.30, 0.28);

    private static OcrResult resultOf(CartoucheField... fields) {
        return new OcrResult(null, null, new CartoucheExtraction(true, List.of(fields)));
    }

    private static OcrResult richResult() {
        return resultOf(
                new CartoucheField("PROJET", "54B"),
                new CartoucheField("EMETTEUR", "LACH"),
                new CartoucheField("PHASE", "EXE"),
                new CartoucheField("INDICE", "A"));
    }

    private static CartoucheLocation locatedBottomRight() {
        return new CartoucheLocation(true, "bottom-right", BOTTOM_RIGHT_BOX, "none", null);
    }

    private static CartoucheLocation notFound() {
        return new CartoucheLocation(false, "unknown", null, "none", null);
    }

    /** Un PDF portant un vrai cartouche en texte : la voie principale doit s'y arrêter. */
    private static byte[] pdfWithCartoucheText(PDRectangle size) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(size);
            doc.addPage(page);
            var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(40, size.getHeight() - 60);
                // Assez de texte pour dépasser le seuil d'exploitabilité de la couche texte.
                cs.showText("PROJET PHASE EMETTEUR LOT ZONE NIVEAU TYPE NUMERO INDICE "
                        + "et de quoi depasser le seuil minimal de caracteres utiles pour ce test, "
                        + "avec un peu de texte de dessin autour comme sur un vrai plan technique.");
                cs.newLineAtOffset(0, -18);
                cs.showText("HUA EXE TRA 36 EXT EX PLA 3100 E");
                cs.endText();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void the_text_layer_is_read_first_and_no_image_is_ever_sent() throws Exception {
        // Voie principale : le texte est déjà dans le PDF, exact. Aucun rendu, aucune image, aucune
        // localisation — le modèle ne sert qu'à reconnaître le cartouche au milieu du reste.
        byte[] pdf = pdfWithCartoucheText(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.analyzeText(any(), anyList())).thenReturn(richResult());
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TEXT_LAYER);
        assertThat(analysis.qualityPassed()).isTrue();
        verify(client).analyzeText(any(), anyList());
        verify(client, never()).locateImage(any(), anyList());
        verify(client, never()).analyzeImage(any(), anyList());
    }

    @Test
    void a_document_without_a_text_layer_falls_back_to_reading_the_image() throws Exception {
        // 13.pdf du corpus : plan scanné, aucun caractère dans le fichier. L'image reprend la main.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(locatedBottomRight());
        when(client.analyzeImage(any(), anyList())).thenReturn(richResult());
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        verify(client, never()).analyzeText(any(), anyList());
    }

    @Test
    void an_unusable_text_reading_falls_back_to_the_image_rather_than_returning_it() throws Exception {
        // Le texte existe mais n'y contient pas de cartouche exploitable (ex. un plan dont le
        // cartouche est en courbes alors que les annotations sont en texte) : l'image tranche.
        byte[] pdf = pdfWithCartoucheText(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.analyzeText(any(), anyList()))
                .thenReturn(new OcrResult(null, null, new CartoucheExtraction(false, List.of())));
        when(client.locateImage(any(), anyList())).thenReturn(locatedBottomRight());
        when(client.analyzeImage(any(), anyList())).thenReturn(richResult());
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.extraction().fields()).hasSize(4);
    }

    @Test
    void located_box_is_read_in_a_single_targeted_call_standard_format() throws Exception {
        // Arrange : page A4, la localisation renvoie une boîte exploitable.
        byte[] pdf = pageBytes(PDRectangle.A4);
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(locatedBottomRight());
        when(client.analyzeImage(any(), anyList())).thenReturn(richResult());
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        // Act
        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Assert : une localisation + UNE lecture ciblée, aucune vague de coins.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.corner()).isEqualTo("bottom-right");
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.attempts()).isEqualTo(1);
        verify(client).locateImage(any(), anyList());
        verify(client).analyzeImage(any(), anyList());
        verify(client, never()).analyzeImagesAsync(anyList(), anyList());
    }

    @Test
    void located_box_is_read_in_a_single_targeted_call_large_plan() throws Exception {
        // Arrange : grand plan (grand côté ~1058 mm > seuil 430 mm), boîte exploitable.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(locatedBottomRight());
        when(client.analyzeImage(any(), anyList())).thenReturn(richResult());
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Deux appels réseau en tout : fini les quatre coins × échantillons du chemin nominal.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.corner()).isEqualTo("bottom-right");
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.attempts()).isEqualTo(1);
        verify(client, never()).analyzeImagesAsync(anyList(), anyList());
    }

    @Test
    void standard_page_without_localization_falls_back_to_full_page_reading() throws Exception {
        // Localisation « pas de cartouche » : chemin historique — lecture pleine page directe.
        byte[] pdf = pageBytes(PDRectangle.A4);
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(notFound());
        when(client.analyzeImage(any(), anyList())).thenReturn(resultOf(new CartoucheField("INDICE", "C")));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.SINGLE_PAGE);
        assertThat(analysis.extraction().fields()).singleElement()
                .satisfies(f -> assertThat(f.label()).isEqualTo("INDICE"));
    }

    @Test
    void implausible_targeted_reading_on_standard_format_falls_back_to_full_page() throws Exception {
        // La boîte est lue mais son contenu ne ressemble pas à un cartouche (2 paires < minimum) :
        // on ne s'arrête pas là, la pleine page (chemin éprouvé des formats standard) tranche.
        byte[] pdf = pageBytes(PDRectangle.A4);
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(locatedBottomRight());
        when(client.analyzeImage(any(), anyList()))
                .thenReturn(resultOf(new CartoucheField("REF", "A")))
                .thenReturn(richResult());
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.SINGLE_PAGE);
        assertThat(analysis.extraction().fields()).hasSize(4);
    }

    @Test
    void unknown_localization_on_large_plan_sweeps_the_corners() throws Exception {
        // Cas 21.pdf : la localisation échoue, mais un coin recadré passe le contrôle qualité.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(notFound());
        OcrResult rich = richResult();
        when(client.analyzeImagesAsync(anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(List.of(rich, rich, rich, rich)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.corner()).isEqualTo("bottom-right"); // premier de CORNER_PRIORITY
        assertThat(analysis.attempts()).isEqualTo(4); // les 4 coins explorés en parallèle
        verify(client, never()).analyzeImage(any(), anyList()); // pas de lecture ciblée sans boîte
    }

    @Test
    void among_passing_corners_the_real_cartouche_beats_the_intervenants_panel() throws Exception {
        // Cas 20.pdf : deux coins passent le contrôle qualité — un panneau d'intervenants (adresses,
        // téléphones) et le vrai cartouche (codes courts). Le score doit faire gagner le cartouche,
        // même si le panneau est un coin de priorité SUPÉRIEURE (bottom-right).
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(notFound());

        OcrResult intervenants = resultOf(
                new CartoucheField("CONSTRUCTEUR", "BYG"),
                new CartoucheField("2, rue Transversale", "92635 Gennevilliers"),
                new CartoucheField("MAINTENEUR", "EXPRIMM"),
                new CartoucheField("COORDONNATEUR SPS", "SOCOTEC"));
        OcrResult cartouche = resultOf(
                new CartoucheField("PHASE", "EXE"),
                new CartoucheField("INDICE", "G"),
                new CartoucheField("LOT", "003"),
                new CartoucheField("N° DOC", "0114"));
        OcrResult empty = new OcrResult(null, null, new CartoucheExtraction(false, List.of()));
        // Vague alignée sur CORNER_PRIORITY = [bottom-right, bottom-left, top-right, top-left].
        when(client.analyzeImagesAsync(anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(List.of(intervenants, cartouche, empty, empty)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.corner()).isEqualTo("bottom-left");
        assertThat(analysis.extraction().fields()).extracting(CartoucheField::label)
                .contains("PHASE", "INDICE");
    }

    @Test
    void implausible_targeted_reading_still_beats_poorer_corners_as_unvalidated_result() throws Exception {
        // La lecture ciblée sort 2 paires (implausible), les coins de repli n'ont rien de mieux :
        // le candidat ciblé, plus riche, part NON validé plutôt que de perdre les données lues.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(locatedBottomRight());
        when(client.analyzeImage(any(), anyList())).thenReturn(resultOf(
                new CartoucheField("N° PLAN", "BEV_0802"),
                new CartoucheField("ECHELLE", "")));
        OcrResult empty = new OcrResult(null, null, new CartoucheExtraction(false, List.of()));
        when(client.analyzeImagesAsync(anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(List.of(empty, empty, empty, empty)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.qualityPassed()).isFalse();
        assertThat(analysis.extraction().fields()).hasSize(2);
    }

    @Test
    void everything_empty_asks_for_tiling() throws Exception {
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(notFound());
        OcrResult empty = new OcrResult(null, null, new CartoucheExtraction(false, List.of()));
        when(client.analyzeImagesAsync(anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(List.of(empty, empty, empty, empty)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.NEEDS_TILING);
    }

    @Test
    void oversized_box_on_large_plan_is_not_read_directly_the_sweep_decides() throws Exception {
        // Une « boîte » couvrant la moitié du plan renverrait l'image dense que la mesure a rejetée
        // (trop de texte = transcription infidèle) : on passe directement au repli par coins.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(
                new CartoucheLocation(true, "center", new CropRegion(0.10, 0.10, 0.80, 0.70), "none", null));
        OcrResult rich = richResult();
        when(client.analyzeImagesAsync(anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(List.of(rich, rich, rich, rich)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.attempts()).isEqualTo(4);
        verify(client, never()).analyzeImage(any(), anyList());
    }

    @Test
    void a_plausible_box_carrying_none_of_the_requested_labels_is_not_trusted() throws Exception {
        // Cas 22.pdf : la boîte localisée tombe sur l'annuaire des intervenants. C'est un formulaire
        // parfaitement plausible (sociétés + rôles), donc le contrôle générique dit oui — mais il ne
        // porte AUCUN des libellés demandés. Le repli doit trancher, et le vrai cartouche gagner.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(locatedBottomRight());
        when(client.analyzeImage(any(), anyList())).thenReturn(resultOf(
                new CartoucheField("CONSTRUCTEUR", "BOUYGUES BATIMENT IDF"),
                new CartoucheField("MAINTENEUR", "EXPRIMM"),
                new CartoucheField("AMO HQE", "ELAN"),
                new CartoucheField("CONTRÔLE TECHNIQUE", "SOCOTEC")));
        OcrResult cartouche = resultOf(
                new CartoucheField("PHASE", "EXE"),
                new CartoucheField("NUMERO", "3316"),
                new CartoucheField("INDICE", "A"),
                new CartoucheField("LOT", "05"));
        OcrResult empty = new OcrResult(null, null, new CartoucheExtraction(false, List.of()));
        when(client.analyzeImagesAsync(anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(List.of(cartouche, empty, empty, empty)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf, List.of(
                new ReadTarget("phase", List.of("Phase")),
                new ReadTarget("numero", List.of("Numéro")),
                new ReadTarget("indice", List.of("Indice"))));

        assertThat(analysis.extraction().fields()).extracting(CartoucheField::label)
                .contains("PHASE", "NUMERO", "INDICE")
                .doesNotContain("CONSTRUCTEUR");
        assertThat(analysis.attempts()).isEqualTo(4);
    }

    @Test
    void one_recognised_label_is_enough_to_keep_the_targeted_reading() throws Exception {
        // Contre-épreuve : le garde-fou ne doit se déclencher QUE sur zéro libellé reconnu. Un seul
        // suffit à garder la lecture ciblée — sinon un cartouche pauvre paierait une vague inutile.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        OcrClient client = mock(OcrClient.class);
        when(client.locateImage(any(), anyList())).thenReturn(locatedBottomRight());
        when(client.analyzeImage(any(), anyList())).thenReturn(resultOf(
                new CartoucheField("Ind.", "A"),
                new CartoucheField("Echelle", "1/50"),
                new CartoucheField("Date", "27/10/2014"),
                new CartoucheField("Indice", "A")));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf, List.of(
                new ReadTarget("indice", List.of("Indice")),
                new ReadTarget("numero", List.of("Numéro"))));

        assertThat(analysis.attempts()).isEqualTo(1);
        verify(client, never()).analyzeImagesAsync(anyList(), anyList());
    }

    @Test
    void disabled_localization_restores_the_legacy_paths() throws Exception {
        // locate-enabled=false : aucun appel de localisation, pleine page directe en standard.
        byte[] pdf = pageBytes(PDRectangle.A4);
        OcrClient client = mock(OcrClient.class);
        when(client.analyzeImage(any(), anyList())).thenReturn(resultOf(new CartoucheField("INDICE", "C")));
        TwoPassCartoucheExtractor extractor =
                new TwoPassCartoucheExtractor(client, null, 3400, 1400, 0.40, false, true);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.SINGLE_PAGE);
        verify(client, never()).locateImage(any(), anyList());
    }

    private static byte[] pageBytes(PDRectangle size) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(size));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }
}
