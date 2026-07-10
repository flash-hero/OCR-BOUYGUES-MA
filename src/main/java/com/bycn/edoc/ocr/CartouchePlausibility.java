package com.bycn.edoc.ocr;

import java.util.List;

/**
 * Contrôle de qualité : une extraction <em>ressemble-t-elle vraiment à un cartouche</em> ?
 *
 * <p>Sert à décider, après la passe 2, si la zone découpée était la bonne. Un cartouche est une
 * petite boîte de type <b>formulaire</b> : plusieurs lignes courtes libellé/valeur (codes, indices,
 * numéros), et non une phrase descriptive (ce qui trahirait un titre de plan capté par erreur).</p>
 *
 * <p>Règle (volontairement simple et explicable) : {@code cartoucheFound}, au moins
 * {@link #MIN_FIELDS} paires, et au moins {@link #MIN_SHORT_CODE_FIELDS} paires « courtes »
 * (libellé et valeur tenant en {@link #MAX_WORDS_SHORT} mots au plus). Le titre d'un plan, lui, se
 * présente comme une ou deux longues phrases : il échoue à ce test.</p>
 */
public final class CartouchePlausibility {

    /** Nombre minimum de paires pour qu'un bloc ressemble à un cartouche (et pas à un titre). */
    static final int MIN_FIELDS = 3;

    /** Une paire est « courte » (code de formulaire) si libellé ET valeur tiennent en tant de mots. */
    static final int MAX_WORDS_SHORT = 6;

    /** Nombre minimum de paires courtes attendues dans un vrai cartouche. */
    static final int MIN_SHORT_CODE_FIELDS = 3;

    private CartouchePlausibility() {
    }

    /** {@code true} si l'extraction a le profil d'un cartouche (formulaire de codes courts). */
    public static boolean looksLikeCartouche(CartoucheExtraction extraction) {
        if (extraction == null || !extraction.cartoucheFound()) {
            return false;
        }
        List<CartoucheField> fields = extraction.fields();
        if (fields.size() < MIN_FIELDS) {
            return false;
        }
        long shortCodeFields = fields.stream().filter(CartouchePlausibility::isShortCodeField).count();
        return shortCodeFields >= MIN_SHORT_CODE_FIELDS;
    }

    /** Une paire « code court » : libellé et valeur brefs (une phrase longue trahirait un titre). */
    static boolean isShortCodeField(CartoucheField field) {
        return wordCount(field.label()) <= MAX_WORDS_SHORT
                && wordCount(field.value()) <= MAX_WORDS_SHORT;
    }

    private static int wordCount(String s) {
        if (s == null) {
            return 0;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }
}
