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
 * Aides PDF <b>orientées octets</b> : tout le cœur métier travaille sur un {@code byte[]} (le
 * contenu du PDF), jamais sur un chemin de fichier. La seule passerelle disque est {@link #read}
 * (lecture d'un fichier d'exemple) : en production, les octets viendront d'un upload REST, mais la
 * logique d'extraction reste identique — elle ne sait pas d'où viennent les octets.
 *
 * <p>Le rendu d'une <b>région</b> de la première page en PNG haute résolution ({@link #renderRegionPng})
 * sert à la fois à la localisation grossière (région = page entière) et à la passe 2 (crop d'un coin).
 * Comme on envoie toujours une <em>image d'une seule page</em> à Mistral, la limite de 30 pages de
 * l'API n'est jamais atteinte, quel que soit le nombre de pages du document source.</p>
 */
public final class PdfSupport {

    private static final String PNG_DATA_URI_PREFIX = "data:image/png;base64,";
    private static final double POINTS_PER_INCH = 72.0;
    private static final double MM_PER_INCH = 25.4;
    private static final float MIN_RENDER_DPI = 72f;
    private static final float MAX_RENDER_DPI = 400f;

    /** Région couvrant la page entière (localisation grossière / lecture pleine page). */
    static final CropRegion FULL_PAGE = new CropRegion(0, 0, 1, 1);

    private PdfSupport() {
    }

    /** Unique passerelle disque : lit un fichier d'exemple en octets (le cœur ne prend que des octets). */
    public static byte[] read(Path pdf) throws IOException {
        return Files.readAllBytes(pdf);
    }

    /** Encode un PNG en data-URI base64, tel que l'attend {@code document.image_url}. */
    public static String toImageDataUri(byte[] pngBytes) {
        return PNG_DATA_URI_PREFIX + Base64.getEncoder().encodeToString(pngBytes);
    }

    /** Plus grand côté de la page (en mm) — sert à décider si le document est un grand format. */
    public static double pageLongSideMm(byte[] pdfBytes, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDRectangle box = doc.getPage(pageIndex).getCropBox();
            double longPt = Math.max(box.getWidth(), box.getHeight());
            return longPt / POINTS_PER_INCH * MM_PER_INCH;
        }
    }

    /** Rend la première page entière en PNG (localisation grossière ou lecture pleine page). */
    public static byte[] renderFirstPagePng(byte[] pdfBytes, int targetLongPx, int maxFullLongPx)
            throws IOException {
        return renderRegionPng(pdfBytes, 0, FULL_PAGE, targetLongPx, maxFullLongPx);
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
    public static byte[] renderRegionPng(byte[] pdfBytes, int pageIndex, CropRegion region,
                                         int targetCropLongPx, int maxFullLongPx) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
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
