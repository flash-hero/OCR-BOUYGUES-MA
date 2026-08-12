package com.bycn.edoc.api;

/**
 * Une valeur officielle acceptée pour un champ, fournie par l'appelant.
 *
 * <p>Équivalent, côté API, d'une ligne de table de référence : dans eDoc ces valeurs viennent des
 * tables de référence du projet (Documentum), pas des CSV embarqués dans le moteur.</p>
 *
 * @param code    la valeur telle qu'elle peut être imprimée dans le cartouche (ex. {@code EXE}, {@code 03})
 * @param libelle son intitulé lisible (ex. {@code Exécution}) — informatif, jamais comparé
 */
public record AllowedValue(String code, String libelle) {
}
