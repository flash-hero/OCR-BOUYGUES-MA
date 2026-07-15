package com.bycn.edoc.classification;

/**
 * Statut d'un champ cible après classification.
 *
 * <p><b>Attention</b> : {@link #AUTO_VALIDATED} n'est <b>jamais</b> posé par ce module. Il est
 * réservé à P4 (validation d'une <i>valeur lue</i> contre les tables de référence du projet),
 * qui n'est pas encore construite. Un champ classé ici reçoit toujours {@link #TO_REVIEW} :
 * la classification établit « ce libellé correspond à ce champ », jamais « cette valeur est
 * correcte ».</p>
 */
public enum FieldStatus {

    /** Valeur confirmée contre une table de référence — posé par P4 uniquement, jamais ici. */
    AUTO_VALIDATED,

    /** Une paire du cartouche a été associée au champ ; la valeur reste à confirmer. */
    TO_REVIEW,

    /** Aucune paire lue ne correspond au champ demandé (ou champ absent de la bibliothèque). */
    MISSING
}
