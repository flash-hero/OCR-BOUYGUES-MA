package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfSupportRenderTest {

    @Test
    void reports_long_side_in_mm_for_an_a4_page(@TempDir Path tmp) throws Exception {
        byte[] pdf = PdfSupport.read(writePage(tmp, PDRectangle.A4)); // 210 x 297 mm
        double longMm = PdfSupport.pageLongSideMm(pdf, 0);
        assertThat(longMm).isCloseTo(297.0, org.assertj.core.api.Assertions.within(1.0));
    }

    @Test
    void renders_a_corner_region_as_a_smaller_png(@TempDir Path tmp) throws Exception {
        byte[] pdf = PdfSupport.read(writePage(tmp, PDRectangle.A4));

        byte[] fullPng = PdfSupport.renderFirstPagePng(pdf, 800, 4000);
        byte[] cropPng = PdfSupport.renderRegionPng(pdf, 0,
                CropRegion.forCorner("bottom-right", 0.40, 0.70), 800, 4000);

        BufferedImage full = ImageIO.read(new ByteArrayInputStream(fullPng));
        BufferedImage crop = ImageIO.read(new ByteArrayInputStream(cropPng));

        assertThat(full).isNotNull();
        assertThat(crop).isNotNull();
        // Le découpage d'un coin est nettement plus petit que la page entière.
        assertThat(crop.getWidth()).isLessThan(full.getWidth());
        assertThat(crop.getHeight()).isLessThan(full.getHeight());
        assertThat(crop.getWidth()).isGreaterThan(0);
        assertThat(crop.getHeight()).isGreaterThan(0);
    }

    private Path writePage(Path dir, PDRectangle size) throws Exception {
        Path pdf = dir.resolve("page.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(size));
            doc.save(pdf.toFile());
        }
        return pdf;
    }
}
