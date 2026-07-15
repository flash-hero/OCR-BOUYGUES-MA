package com.bycn.edoc.validation;

import com.bycn.edoc.classification.LabelNormalizer;
import java.util.regex.Pattern;

/**
 * Met une <i>valeur lue</i> et un <i>code</i> de table de référence sous forme comparable avant la
 * correspondance floue.
 *
 * <p>Deux étapes, dans cet ordre :</p>
 * <ol>
 *   <li>{@link LabelNormalizer#normalize(String)} — accents repliés, minuscules, espaces compactés.
 *       Réutilisé tel quel (une seule source de vérité) : {@code FuzzySearch.ratio} est sensible à
 *       la casse et aux accents de la même façon ici qu'en P3, la parade est donc la même.</li>
 *   <li>Zéros de tête retirés, <b>uniquement</b> si la chaîne est purement numérique.</li>
 * </ol>
 *
 * <p><b>Pourquoi le retrait des zéros de tête — mesuré, pas supposé.</b> Cas réel du corpus :
 * {@code 16.pdf} affiche {@code Lot : 003} (voir {@code docs/howtorun.md}), face au code
 * {@code 03} de {@code LOT.csv}. Mesures brutes de {@code FuzzySearch.ratio} :</p>
 * <pre>
 *   ratio("003", "03") = 80   &lt; seuil 85  -&gt; le bon code ne serait PAS reconnu
 *   ratio("003", "00") = 80   &lt;- MÊME score, sur le MAUVAIS code
 * </pre>
 * <p>Le second point est le plus grave : {@code 003} est à égalité parfaite entre {@code 03}
 * (« Terrassements », correct) et {@code 00} (« Généralités », faux). Un simple abaissement du
 * seuil aurait donc rendu possible une auto-validation silencieuse du mauvais lot, en fonction du
 * seul ordre de parcours de la table — exactement le piège documenté dans {@code CLAUDE.md}
 * (« un contrôle qui dit oui ne veut pas dire que c'est correct »). Après retrait des zéros de
 * tête, l'ambiguïté disparaît au lieu d'être arbitrée au hasard :</p>
 * <pre>
 *   ratio("3", "3") = 100  -&gt; 03 Terrassements, reconnu
 *   ratio("3", "0") =   0  -&gt; 00 Généralités, écarté sans ambiguïté
 * </pre>
 *
 * <p><b>Pourquoi seulement le purement numérique.</b> Sur un code alphanumérique, un zéro de tête
 * porte du sens et n'est pas une variante d'écriture : {@code N-1} (NIVEAU), {@code A-B} (ZONE) ou
 * {@code O11} (TYPE) ne doivent jamais être charcutés. La règle ne s'applique donc qu'aux chaînes
 * qui ne contiennent que des chiffres, où {@code 003} et {@code 3} désignent réellement la même
 * chose. Chaque côté est normalisé indépendamment : une chaîne n'est réduite que si elle est
 * elle-même purement numérique.</p>
 *
 * <p><b>Vérifié sur les tables réelles</b> : après cette normalisation, aucune paire de codes
 * distincts d'une même table ({@code PHASE}, {@code NIVEAU}, {@code ZONE}, {@code LOT},
 * {@code TYPE}) n'atteint 85 l'une contre l'autre. Au seuil retenu, deux codes ne peuvent donc pas
 * se disputer une même valeur : le « meilleur score » n'est pas un arbitrage arbitraire.</p>
 */
final class ValueNormalizer {

    private static final Pattern PURELY_NUMERIC = Pattern.compile("\\d+");

    private ValueNormalizer() {
    }

    /** Chaîne vide si {@code raw} est nul ou blanc — jamais {@code null}. */
    static String normalize(String raw) {
        String base = LabelNormalizer.normalize(raw);
        if (!PURELY_NUMERIC.matcher(base).matches()) {
            return base;
        }
        String withoutLeadingZeros = base.replaceFirst("^0+", "");
        // "00" et "000" se réduisent à "" : on garde un chiffre, le code 00 reste le code 00.
        return withoutLeadingZeros.isEmpty() ? "0" : withoutLeadingZeros;
    }
}
