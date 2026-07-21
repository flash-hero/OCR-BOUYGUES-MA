package com.bycn.edoc.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consensus sur N échantillons OCR d'un <b>même</b> appel.
 *
 * <p>L'endpoint {@code /v1/ocr} n'expose <em>aucun</em> paramètre de déterminisme (ni
 * {@code temperature}, ni {@code seed}, ni {@code random_seed} — vérifié sur la référence API
 * Mistral). Deux requêtes strictement identiques peuvent donc renvoyer des résultats différents :
 * empiriquement, 12.pdf a basculé {@code cartoucheFound} true→false entre deux runs, et 15.pdf est
 * passé de 77 à 19 paires. On échantillonne donc le même appel plusieurs fois et on retient un
 * <em>représentant robuste</em>.</p>
 *
 * <p><b>On ne fusionne ni ne filtre jamais les paires</b> (principe ÉA1 : extraction générique,
 * recopie verbatim, la classification vient après). La méthode renvoie l'<em>indice</em> de
 * l'échantillon retenu : l'appelant conserve ainsi sa réponse brute telle quelle, sans qu'aucune
 * paire ne soit inventée ni supprimée.</p>
 *
 * <p>Règle (volontairement simple et explicable) :</p>
 * <ol>
 *   <li>vote majoritaire sur {@code cartoucheFound} (égalité → {@code true}) ;</li>
 *   <li>parmi les échantillons du camp majoritaire, on prend celui dont le <b>nombre de paires</b>
 *       est la <b>médiane basse</b> : ni l'échantillon dégénéré (0 paire), ni le sur-extrait (77),
 *       mais le milieu.</li>
 * </ol>
 */
public final class OcrConsensus {

    private OcrConsensus() {
    }

    /**
     * Indice de l'extraction représentative parmi des échantillons du même appel d'analyse.
     *
     * @throws IllegalArgumentException si la liste est vide
     */
    public static int pickExtractionIndex(List<CartoucheExtraction> samples) {
        requireNonEmpty(samples);
        if (samples.size() == 1) {
            return 0;
        }
        List<Boolean> flags = new ArrayList<>(samples.size());
        for (CartoucheExtraction e : samples) {
            flags.add(e != null && e.cartoucheFound());
        }
        boolean majority = majorityTrue(flags);

        // Indices du camp majoritaire, triés par nombre de paires croissant (puis indice, pour un
        // départage stable et reproductible), puis médiane basse.
        List<Integer> camp = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            if (flags.get(i) == majority) {
                camp.add(i);
            }
        }
        camp.sort(Comparator
                .comparingInt((Integer i) -> fieldCount(samples.get(i)))
                .thenComparingInt(i -> i));
        return camp.get((camp.size() - 1) / 2);
    }

    /**
     * Indice de la localisation représentative (passe 1) parmi des échantillons du même appel :
     * camp majoritaire sur {@code cartoucheFound}, puis <b>coin le plus fréquent</b> (normalisé,
     * insensible à la casse ; égalité → premier apparu). Stabilise la localisation, qui flotte d'un
     * run à l'autre (ex. 12.pdf : « top-center » vu une fois là où les autres disaient « top-left »).
     *
     * @throws IllegalArgumentException si la liste est vide
     */
    public static int pickLocationIndex(List<CartoucheLocation> samples) {
        requireNonEmpty(samples);
        if (samples.size() == 1) {
            return 0;
        }
        List<Boolean> flags = new ArrayList<>(samples.size());
        for (CartoucheLocation l : samples) {
            flags.add(l != null && l.cartoucheFound());
        }
        boolean majority = majorityTrue(flags);

        // Coin le plus fréquent parmi le camp majoritaire (ordre d'insertion préservé pour départager).
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < samples.size(); i++) {
            if (flags.get(i) == majority) {
                counts.merge(corner(samples.get(i)), 1, Integer::sum);
            }
        }
        String bestCorner = null;
        int best = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestCorner = e.getKey();
            }
        }
        for (int i = 0; i < samples.size(); i++) {
            if (flags.get(i) == majority && corner(samples.get(i)).equals(bestCorner)) {
                return i;
            }
        }
        return 0; // filet de sécurité (inatteignable : bestCorner vient du camp majoritaire)
    }

    /** {@code true} si au moins la moitié des drapeaux sont vrais (égalité → true). */
    private static boolean majorityTrue(List<Boolean> flags) {
        long trues = flags.stream().filter(Boolean::booleanValue).count();
        return trues * 2 >= flags.size();
    }

    private static int fieldCount(CartoucheExtraction e) {
        return (e == null || e.fields() == null) ? 0 : e.fields().size();
    }

    private static String corner(CartoucheLocation l) {
        if (l == null || l.corner() == null) {
            return "unknown";
        }
        String c = l.corner().trim().toLowerCase();
        return c.isEmpty() ? "unknown" : c;
    }

    private static void requireNonEmpty(List<?> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("consensus : au moins un échantillon est requis");
        }
    }
}
