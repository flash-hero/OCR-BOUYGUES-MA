package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.List;
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
        when(client.locateImage(any())).thenReturn(new CartoucheLocation(true, "bottom-right", null));
        when(client.analyzeImage(any())).thenReturn(resultOf(
                new CartoucheField("PROJET", "54B"),
                new CartoucheField("EMETTEUR", "LACH"),
                new CartoucheField("PHASE", "EXE"),
                new CartoucheField("INDICE", "A")));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        // Act
        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Assert : passe 1 (localisation) puis passe 2 (crop) qui passe le contrôle qualité.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.corner()).isEqualTo("bottom-right");
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.attempts()).isEqualTo(1);
        verify(client).locateImage(any());
        verify(client).analyzeImage(any());
    }

    @Test
    void unknown_localization_sweeps_the_corners_then_asks_for_tiling_only_if_none_pass() throws Exception {
        // Localisation 'unknown' : on ne renonce pas tout de suite, on balaie les 4 coins prioritaires.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        MistralOcrClient client = mock(MistralOcrClient.class);
        when(client.locateImage(any())).thenReturn(new CartoucheLocation(false, "unknown", null));
        // Aucun coin ne ressemble à un cartouche.
        when(client.analyzeImage(any()))
                .thenReturn(new OcrResult(null, null, new CartoucheExtraction(false, List.of())));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Ce n'est qu'après échec des 4 coins qu'on signale le besoin de tuiles.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.NEEDS_TILING);
        verify(client, times(4)).analyzeImage(any());
    }

    @Test
    void unknown_localization_still_recovers_when_a_corner_crop_passes_quality() throws Exception {
        // Cas 21.pdf : la localisation pleine page échoue, mais l'extraction d'un coin recadré réussit.
        byte[] pdf = pageBytes(new PDRectangle(3000, 2000));
        MistralOcrClient client = mock(MistralOcrClient.class);
        when(client.locateImage(any())).thenReturn(new CartoucheLocation(false, "unknown", null));
        when(client.analyzeImage(any())).thenReturn(resultOf(
                new CartoucheField("PROJET", "54B"),
                new CartoucheField("EMETTEUR", "LACH"),
                new CartoucheField("PHASE", "EXE"),
                new CartoucheField("INDICE", "A")));
        TwoPassCartoucheExtractor extractor = new TwoPassCartoucheExtractor(client);

        CartoucheAnalysis analysis = extractor.extract(pdf);

        // Malgré 'unknown', le balayage récupère le cartouche au premier coin prioritaire.
        assertThat(analysis.mode()).isEqualTo(CartoucheAnalysis.Mode.TWO_PASS_CROP);
        assertThat(analysis.qualityPassed()).isTrue();
        assertThat(analysis.corner()).isEqualTo("bottom-right"); // premier de CORNER_PRIORITY
        assertThat(analysis.attempts()).isEqualTo(1);
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
