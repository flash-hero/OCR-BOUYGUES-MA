package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * Vérifie que le cœur travaille sur des <b>octets</b> et n'envoie jamais que des images d'une seule
 * page (jamais de PDF multi-pages), sur les deux chemins : format standard et grand plan.
 */
class TwoPassCartoucheExtractorTest {

    private static OcrResult resultOf(CartoucheField... fields) {
        return new OcrResult(null, null, new CartoucheExtraction(true, List.of(fields)));
    }

    @Test
    void standard_page_is_read_from_a_single_full_page_image_no_localization() throws Exception {
        // Arrange : page A4 (format standard) fournie en octets.
        byte[] pdf = pageBytes(PDRectangle.A4);
        MistralOcrClient client = mock(MistralOcrClient.class);
        when(client.analyzeImage(any())).thenReturn(resultOf(new CartoucheField("INDICE", "C")));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        // Act
        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Assert : lecture pleine page via une image, aucune passe de localisation.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.SINGLE_PAGE);
        assertThat(analysis.extraction().fields()).singleElement()
                .satisfies(f -> assertThat(f.label()).isEqualTo("INDICE"));
        verify(client).analyzeImage(any());
        verify(client, never()).locateImage(any());
    }

    @Test
    void large_plan_localizes_then_extracts_the_crop_on_the_returned_corner() throws Exception {
        // Arrange : grande page (grand côté ~1058 mm > seuil 430 mm).
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        MistralOcrClient client = mock(MistralOcrClient.class);
        when(client.locateImageAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(new CartoucheLocation(true, "bottom-right", null)));
        OcrResult rich = resultOf(
                new CartoucheField("PROJET", "54B"),
                new CartoucheField("EMETTEUR", "LACH"),
                new CartoucheField("PHASE", "EXE"),
                new CartoucheField("INDICE", "A"));
        // Une seule vague : localisation ET les 4 coins (consensus chacun) en même temps.
        when(client.analyzeImagesAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(rich, rich, rich, rich)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        // Act
        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Assert : localisation + vague de coins concurrente ; le coin retenu passe le contrôle qualité.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.corner()).isEqualTo("bottom-right");
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.attempts()).isEqualTo(4); // les 4 coins évalués en parallèle
        verify(client).locateImageAsync(any());
        verify(client).analyzeImagesAsync(any());
        verify(client, never()).analyzeImage(any()); // pas de seconde vague de confirmation
    }

    @Test
    void unknown_localization_sweeps_the_corners_then_asks_for_tiling_only_if_none_pass() throws Exception {
        // Localisation 'unknown' : on ne renonce pas tout de suite, on balaie les 4 coins prioritaires.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        MistralOcrClient client = mock(MistralOcrClient.class);
        when(client.locateImageAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(new CartoucheLocation(false, "unknown", null)));
        // Aucun des 4 coins (évalués en parallèle) ne renvoie quoi que ce soit.
        OcrResult empty = new OcrResult(null, null, new CartoucheExtraction(false, List.of()));
        when(client.analyzeImagesAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(empty, empty, empty, empty)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Ce n'est que lorsque TOUS les coins sont vides qu'on signale le besoin de tuiles.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.NEEDS_TILING);
        verify(client).analyzeImagesAsync(any());
    }

    @Test
    void unknown_localization_still_recovers_when_a_corner_crop_passes_quality() throws Exception {
        // Cas 21.pdf : la localisation pleine page échoue, mais l'extraction d'un coin recadré réussit.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        MistralOcrClient client = mock(MistralOcrClient.class);
        when(client.locateImageAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(new CartoucheLocation(false, "unknown", null)));
        OcrResult rich = resultOf(
                new CartoucheField("PROJET", "54B"),
                new CartoucheField("EMETTEUR", "LACH"),
                new CartoucheField("PHASE", "EXE"),
                new CartoucheField("INDICE", "A"));
        when(client.analyzeImagesAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(rich, rich, rich, rich)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Malgré 'unknown', la vague de coins récupère le cartouche au premier coin prioritaire.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.corner()).isEqualTo("bottom-right"); // premier de CORNER_PRIORITY
        assertThat(analysis.attempts()).isEqualTo(4); // les 4 coins explorés
    }

    @Test
    void among_passing_corners_the_real_cartouche_beats_the_intervenants_panel() throws Exception {
        // Cas 20.pdf : deux coins passent le contrôle qualité — un panneau d'intervenants (adresses,
        // téléphones) et le vrai cartouche (codes courts). Le score doit faire gagner le cartouche,
        // même si le panneau est un coin de priorité SUPÉRIEURE (bottom-right).
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        MistralOcrClient client = mock(MistralOcrClient.class);
        when(client.locateImageAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(new CartoucheLocation(false, "unknown", null)));

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
        when(client.analyzeImagesAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(intervenants, cartouche, empty, empty)));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Le cartouche (bottom-left) l'emporte sur le panneau d'intervenants (bottom-right), pourtant prioritaire.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.corner()).isEqualTo("bottom-left");
        assertThat(analysis.extraction().fields()).extracting(CartoucheField::label)
                .contains("PHASE", "INDICE");
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
