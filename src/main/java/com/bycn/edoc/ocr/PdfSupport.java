package com.bycn.edoc.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Petites aides PDF : lecture des octets, encodage base64 en data-URI (format attendu par
 * l'endpoint {@code /v1/ocr}), comptage/dimensions de pages et rendu d'une <b>région</b> de page
 * en PNG haute résolution (pour la passe 2 sur les grands plans).
 */
public final class PdfSupport {

    private static final String PDF_DATA_URI_PREFIX = "data:application/pdf;base64,";
    private static final String PNG_DATA_URI_PREFIX = "data:image/png;base64,";
    private static final double POINTS_PER_INCH = 72.0;
    private static final double MM_PER_INCH = 25.4;
    private static final float MIN_RENDER_DPI = 72f;
    private static final float MAX_RENDER_DPI = 300f;

    private PdfSupport() {
    }

    public static byte[] read(Path pdf) throws IOException {
        return Files.readAllBytes(pdf);
    }

    /** Encode le PDF en data-URI base64, tel que l'attend {@code document.document_url}. */
    public static String toBase64DataUri(byte[] pdfBytes) {
        return PDF_DATA_URI_PREFIX + Base64.getEncoder().encodeToString(pdfBytes);
    }

    /** Encode un PNG en data-URI base64, tel que l'attend {@code document.image_url}. */
    public static String toImageDataUri(byte[] pngBytes) {
        return PNG_DATA_URI_PREFIX + Base64.getEncoder().encodeToString(pngBytes);
    }

    /** Nombre de pages du PDF (utile pour borner l'annotation et pour l'affichage). */
    public static int pageCount(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    /** Plus grand côté de la page (en mm) — sert à décider si le document est un grand format. */
    public static double pageLongSideMm(Path pdf, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDRectangle box = doc.getPage(pageIndex).getCropBox();
            double longPt = Math.max(box.getWidth(), box.getHeight());
            return longPt / POINTS_PER_INCH * MM_PER_INCH;
        }
    }

    /**
     * Rend une <b>région</b> de la page en PNG, à une résolution choisie pour que le découpage
     * envoyé fasse environ {@code targetCropLongPx} pixels sur son grand côté (assez pour rester
     * lisible après le redimensionnement interne de Mistral), tout en bornant la taille du rendu
     * pleine page à {@code maxFullLongPx} pixels (garde-fou mémoire).
     *
     * <p>Le rendu passe par {@link PDFRenderer#renderImageWithDPI} qui corrige déjà l'orientation
     * (rotation {@code /Rotate}) : la région est ensuite découpée en coordonnées pixel, origine en
     * haut-gauche, ce qui correspond aux fractions de {@link CropRegion}.</p>
     */
    public static byte[] renderRegionPng(Path pdf, int pageIndex, CropRegion region,
                                         int targetCropLongPx, int maxFullLongPx) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDPage page = doc.getPage(pageIndex);
            PDRectangle box = page.getCropBox();
            double pageLongPt = Math.max(box.getWidth(), box.getHeight());
            double cropLongFrac = Math.max(region.w(), region.h());
            double cropLongPt = Math.max(1.0, cropLongFrac * pageLongPt);

            double dpiForCrop = targetCropLongPx * POINTS_PER_INCH / cropLongPt;
            double dpiForFullCap = maxFullLongPx * POINTS_PER_INCH / pageLongPt;
            float dpi = (float) Math.min(dpiForCrop, dpiForFullCap);
            dpi = Math.max(MIN_RENDER_DPI, Math.min(dpi, MAX_RENDER_DPI));

            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage full = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
            int fullW = full.getWidth();
            int fullH = full.getHeight();

            int x = clamp((int) Math.round(region.x() * fullW), 0, fullW - 1);
            int y = clamp((int) Math.round(region.y() * fullH), 0, fullH - 1);
            int w = clamp((int) Math.round(region.w() * fullW), 1, fullW - x);
            int h = clamp((int) Math.round(region.h() * fullH), 1, fullH - y);

            BufferedImage crop = full.getSubimage(x, y, w, h);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(crop, "png", bos);
            return bos.toByteArray();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
