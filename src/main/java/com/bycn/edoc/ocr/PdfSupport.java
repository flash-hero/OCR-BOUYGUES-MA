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
import org.apache.pdfbox.rendering.RenderDestination;

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
    private static final String JPEG_DATA_URI_PREFIX = "data:image/jpeg;base64,";
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

    /** Encode une image (PNG ou JPEG, reconnu à ses octets de tête) en data-URI base64. */
    public static String toImageDataUri(byte[] imageBytes) {
        boolean jpeg = imageBytes.length > 2
                && (imageBytes[0] & 0xFF) == 0xFF && (imageBytes[1] & 0xFF) == 0xD8;
        return (jpeg ? JPEG_DATA_URI_PREFIX : PNG_DATA_URI_PREFIX)
                + Base64.getEncoder().encodeToString(imageBytes);
    }

    /**
     * Encode en JPEG (qualité 0.8) — pour la <b>localisation</b> uniquement. Un plan dense en PNG
     * sans perte pèse plusieurs Mo, et ces mégaoctets partent en base64 dans la requête : l'envoi
     * domine alors l'appel. La localisation ne lit aucun texte, une compression avec pertes ne lui
     * enlève rien. Les images de <b>lecture</b>, elles, restent en PNG (fidélité des petits caractères).
     */
    static byte[] toJpegBytes(BufferedImage image) throws IOException {
        BufferedImage rgb = image;
        // Le JPEG accepte le niveaux-de-gris nativement (fichier ~3x plus léger qu'en RGB) ; seuls
        // les types exotiques (alpha, palette) passent par une conversion.
        if (image.getType() != BufferedImage.TYPE_INT_RGB && image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(bos);
        try {
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.8f);
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
            out.close();
        }
        return bos.toByteArray();
    }

    /** Sous-image correspondant à une région fractionnaire (bornée aux dimensions de l'image). */
    static BufferedImage crop(BufferedImage image, CropRegion region) {
        int fullW = image.getWidth();
        int fullH = image.getHeight();
        int x = clamp((int) Math.round(region.x() * fullW), 0, fullW - 1);
        int y = clamp((int) Math.round(region.y() * fullH), 0, fullH - 1);
        int w = clamp((int) Math.round(region.w() * fullW), 1, fullW - x);
        int h = clamp((int) Math.round(region.h() * fullH), 1, fullH - y);
        return image.getSubimage(x, y, w, h);
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
     * Rendu brut d'une page en image, pour <b>analyse locale</b> uniquement (jamais envoyé au
     * modèle) : voir {@link PageInkBounds}. Basse résolution assumée — on cherche où il y a de
     * l'encre, pas à lire quoi que ce soit.
     */
    static BufferedImage renderPageImage(byte[] pdfBytes, int pageIndex, int targetLongPx)
            throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDRectangle box = doc.getPage(pageIndex).getCropBox();
            double pageLongPt = Math.max(box.getWidth(), box.getHeight());
            float dpi = (float) (targetLongPx * POINTS_PER_INCH / Math.max(1.0, pageLongPt));
            dpi = Math.max(MIN_RENDER_DPI, Math.min(dpi, MAX_RENDER_DPI));
            return new PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);
        }
    }

    /**
     * Rend une <b>région</b> de la page en ne rastérisant <b>que cette région</b> : la boîte de
     * rognage de la page est réduite à la région avant le rendu. Sur un A0 dense, rendre la page
     * entière en haute résolution coûte plusieurs dizaines de secondes ; rendre la seule zone du
     * cartouche en coûte une fraction — c'est ce qui tient le budget de latence.
     *
     * <p>Mêmes pixels que {@link #renderRegionPng} : le DPI est choisi avec les mêmes plafonds
     * (résolution visée du découpage, plafond pleine page, {@link #MAX_RENDER_DPI}). Seule la
     * surface rastérisée change.</p>
     *
     * <p><b>Rotation.</b> {@code region} est exprimée en fractions de l'image <em>rendue</em>
     * (rotation {@code /Rotate} déjà appliquée, origine en haut-gauche), alors que la boîte de
     * rognage vit dans l'espace <em>non tourné</em> de la page (origine en bas-gauche). La
     * conversion est faite ici pour les quatre rotations possibles — vérifiée par un test à pastille
     * de couleur, pas au jugé : le corpus contient des pages en /Rotate 90, 180 et 270.</p>
     */
    public static byte[] renderRegionDirectPng(byte[] pdfBytes, int pageIndex, CropRegion region,
                                               int targetCropLongPx, int maxFullLongPx) throws IOException {
        BufferedImage crop = renderRegionDirect(pdfBytes, pageIndex, region, targetCropLongPx, maxFullLongPx, false);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(crop, "png", bos);
        return bos.toByteArray();
    }

    /**
     * Même rendu direct d'une région, en {@link BufferedImage}, à deux niveaux de qualité.
     *
     * <p>{@code fast=false} — <b>lecture</b> : RGB, qualité export, images embarquées à pleine
     * résolution. Ce sont exactement les pixels du rendu historique : mesuré, changer l'apparence de
     * l'image de lecture change ce que le modèle lit (un rendu allégé de 16.pdf a fait passer la
     * lecture de 13 à 29 paires, en faisant remonter les lignes du tableau de révisions — plus de
     * paires n'est pas mieux, et chaque paire coûte du temps de génération).</p>
     *
     * <p>{@code fast=true} — <b>localisation</b> : niveaux de gris, destination écran,
     * sous-échantillonnage autorisé. La localisation ne lit aucun texte ; mesuré sur les A0 les plus
     * lourds, la même page se peint 25 à 40&nbsp;% plus vite et l'image pèse moitié moins.</p>
     */
    static BufferedImage renderRegionDirect(byte[] pdfBytes, int pageIndex, CropRegion region,
                                            int targetCropLongPx, int maxFullLongPx, boolean fast)
            throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDPage page = doc.getPage(pageIndex);
            PDRectangle box = page.getCropBox();
            int rotation = ((page.getRotation() % 360) + 360) % 360;

            double fu1 = region.x();
            double fu2 = region.x() + region.w();
            double fv1 = region.y();
            double fv2 = region.y() + region.h();
            // Fractions de la boîte non tournée (x vers la droite, y vers le HAUT).
            double fxMin;
            double fxMax;
            double fyMin;
            double fyMax;
            switch (rotation) {
                case 90 -> {
                    fxMin = fv1; fxMax = fv2; fyMin = fu1; fyMax = fu2;
                }
                case 180 -> {
                    fxMin = 1 - fu2; fxMax = 1 - fu1; fyMin = fv1; fyMax = fv2;
                }
                case 270 -> {
                    fxMin = 1 - fv2; fxMax = 1 - fv1; fyMin = 1 - fu2; fyMax = 1 - fu1;
                }
                default -> {
                    fxMin = fu1; fxMax = fu2; fyMin = 1 - fv2; fyMax = 1 - fv1;
                }
            }
            float cropX = (float) (box.getLowerLeftX() + fxMin * box.getWidth());
            float cropY = (float) (box.getLowerLeftY() + fyMin * box.getHeight());
            float cropW = (float) Math.max(1.0, (fxMax - fxMin) * box.getWidth());
            float cropH = (float) Math.max(1.0, (fyMax - fyMin) * box.getHeight());
            page.setCropBox(new PDRectangle(cropX, cropY, cropW, cropH));

            double pageLongPt = Math.max(box.getWidth(), box.getHeight());
            double cropLongPt = Math.max(cropW, cropH);
            double dpiForCrop = targetCropLongPx * POINTS_PER_INCH / cropLongPt;
            double dpiForFullCap = maxFullLongPx * POINTS_PER_INCH / pageLongPt;
            float dpi = (float) Math.min(dpiForCrop, dpiForFullCap);
            dpi = Math.max(MIN_RENDER_DPI, Math.min(dpi, MAX_RENDER_DPI));

            PDFRenderer renderer = new PDFRenderer(doc);
            renderer.setSubsamplingAllowed(fast);
            renderer.setDefaultDestination(fast ? RenderDestination.VIEW : RenderDestination.EXPORT);
            return renderer.renderImageWithDPI(pageIndex, dpi, fast ? ImageType.GRAY : ImageType.RGB);
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
