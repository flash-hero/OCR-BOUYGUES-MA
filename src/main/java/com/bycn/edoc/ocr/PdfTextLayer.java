package com.bycn.edoc.ocr;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Le <b>texte déjà contenu dans le PDF</b>, extrait localement — sans appel réseau, sans OCR.
 *
 * <p><b>Pourquoi c'est la voie principale.</b> Un plan produit par un logiciel de CAO n'est pas une
 * photo : c'est du dessin vectoriel accompagné de son texte. Mesuré sur le corpus, 27 documents sur
 * 28 portent une couche texte (12.pdf : 16 447 caractères ; 24.pdf : 16 951). Faire relire ce texte
 * par un modèle de vision, c'est le retranscrire une seconde fois — plus lentement, plus cher, et
 * avec le risque d'erreurs que la première transcription n'avait pas (caractère confondu, cellules
 * voisines fusionnées, valeur multi-lignes tronquée). Ici le texte sort <b>exact</b>.</p>
 *
 * <p><b>La mise en page est conservée</b> ({@code setSortByPosition}) : les colonnes d'un cartouche
 * restent alignées, et une ligne d'en-têtes reste au-dessus de sa ligne de valeurs. C'est ce qui rend
 * l'appariement libellé/valeur possible. Extrait réel de 12.pdf :</p>
 *
 * <pre>
 * PROJET PHASE EMETTEUR LOT ZONE NIVEAU TYPE NUMERO INDICE
 * HUA    EXE   TRA      36  EXT  EX     PLA  3100   E
 * </pre>
 *
 * <p>Un document sans couche texte (scan, ou texte vectorisé en courbes — {@code 13.pdf} du corpus)
 * renvoie un texte vide : l'appelant bascule alors sur la lecture par l'image.</p>
 */
public final class PdfTextLayer {

    /**
     * En dessous de ce nombre de caractères, la couche texte est jugée inexploitable : quelques
     * fragments d'annotation ne suffisent pas à contenir un cartouche, et s'en contenter ferait
     * manquer un document que l'image aurait lu. Le repli par l'image reste alors la voie normale.
     */
    static final int MIN_USEFUL_CHARS = 200;

    private PdfTextLayer() {
    }

    /**
     * Le texte de la page, mise en page préservée, ou une chaîne <b>vide</b> si le document n'en a
     * pas d'exploitable (ou si l'extraction échoue — jamais bloquant : on retombe sur l'image).
     */
    public static String of(byte[] pdfBytes, int pageIndex) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            // Ordonner par position, et non par ordre d'écriture dans le fichier : sans cela, les
            // colonnes d'un tableau ressortent mélangées et l'appariement devient impossible.
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return isUseful(text) ? text : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** {@code true} si le texte extrait est assez fourni pour espérer y trouver un cartouche. */
    static boolean isUseful(String text) {
        return text != null && text.strip().length() >= MIN_USEFUL_CHARS;
    }
}
