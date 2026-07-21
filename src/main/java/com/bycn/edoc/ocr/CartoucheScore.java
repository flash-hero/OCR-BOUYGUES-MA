package com.bycn.edoc.ocr;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Score de « cartouche-ité » d'une extraction : à quel point un bloc ressemble au <b>cartouche
 * d'identification</b> (codes courts : phase, indice, N°, échelle, lot, zone…) plutôt qu'à un
 * <em>panneau d'intervenants</em> (adresses, téléphones, e-mails, noms de sociétés) ou à une légende.
 *
 * <p>Sert à <b>choisir entre plusieurs coins</b> d'un grand plan, tous évalués en parallèle : le
 * panneau des intervenants passe le simple contrôle {@link CartouchePlausibility} (il a des paires
 * courtes remplies), mais ce n'est pas le cartouche. On ne filtre ni ne supprime aucune paire (principe
 * ÉA1 : extraction générique) — on <b>classe les coins</b> et on retient le plus cartouche.</p>
 *
 * <p>Règle additive et explicable, par paire : bonus si le libellé est un champ d'identification connu
 * ou si la valeur est un code court ; malus si le libellé/valeur porte des signaux d'adresse/contact
 * (rue, cedex, téléphone, e-mail, code postal, rôle d'intervenant) ou si la valeur est une longue phrase.</p>
 */
public final class CartoucheScore {

    /** Libellés typiques d'un cartouche d'identification (comparaison accent/casse-insensible, sous-chaîne). */
    private static final List<String> ID_LABELS = List.of(
            "phase", "indice", "revision", "echelle", "format", "emetteur", "lot", "zone",
            "niveau", "numero", "n°", "affaire", "projet", "batiment", "type", "date");

    /** Signaux forts d'un panneau d'intervenants / bloc d'adresses (pas un cartouche). */
    private static final List<String> CONTACT_TERMS = List.of(
            "rue", "avenue", "boulevard", "cedex", "immeuble", "constructeur", "mainteneur",
            "maitrise", "maitre d", "controle technique", "coordonnateur", "bureau d");

    static final int ID_LABEL_POINTS = 2;
    static final int SHORT_CODE_POINTS = 1;
    static final int CONTACT_PENALTY = 3;
    static final int LONG_VALUE_PENALTY = 1;
    static final int SHORT_CODE_MAX_WORDS = 3;
    static final int SHORT_CODE_MAX_CHARS = 14;
    static final int LONG_VALUE_WORDS = 5;
    static final int PHONE_MIN_DIGITS = 7;

    private CartoucheScore() {
    }

    /** Score global : somme des scores de paires. Plus haut = plus « cartouche d'identification ». */
    public static double score(CartoucheExtraction extraction) {
        if (extraction == null || extraction.fields() == null) {
            return 0;
        }
        double total = 0;
        for (CartoucheField field : extraction.fields()) {
            total += scoreField(field);
        }
        return total;
    }

    private static double scoreField(CartoucheField field) {
        String label = norm(field.label());
        String value = norm(field.value());
        double s = 0;
        if (containsAny(label, ID_LABELS)) {
            s += ID_LABEL_POINTS;
        }
        if (isShortCode(field.value())) {
            s += SHORT_CODE_POINTS;
        }
        boolean contact = containsAny(label, CONTACT_TERMS) || containsAny(value, CONTACT_TERMS)
                || looksLikePhone(value) || value.contains("@") || looksLikePostal(value);
        if (contact) {
            s -= CONTACT_PENALTY;
        }
        if (wordCount(field.value()) > LONG_VALUE_WORDS) {
            s -= LONG_VALUE_PENALTY;
        }
        return s;
    }

    /** Valeur brève de type code de formulaire : non vide, peu de mots, courte, avec de l'alphanumérique. */
    private static boolean isShortCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String t = raw.trim();
        return t.length() <= SHORT_CODE_MAX_CHARS
                && wordCount(t) <= SHORT_CODE_MAX_WORDS
                && t.chars().anyMatch(Character::isLetterOrDigit);
    }

    /** Beaucoup de chiffres (téléphone/fax) : signal contact. */
    private static boolean looksLikePhone(String v) {
        long digits = v.chars().filter(Character::isDigit).count();
        return digits >= PHONE_MIN_DIGITS;
    }

    /** Un code postal français (5 chiffres délimités) : signal adresse. */
    private static boolean looksLikePostal(String v) {
        return v.matches(".*\\b\\d{5}\\b.*");
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int wordCount(String s) {
        if (s == null) {
            return 0;
        }
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /** Minuscule + suppression des accents (ÉMETTEUR → emetteur), pour une comparaison robuste. */
    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT);
    }
}
