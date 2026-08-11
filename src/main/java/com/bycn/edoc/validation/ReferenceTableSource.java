package com.bycn.edoc.validation;

import java.util.List;

/**
 * D'où viennent les valeurs officielles d'un champ, au moment de valider une valeur lue.
 *
 * <p><b>Pourquoi cette indirection.</b> {@link ReferenceTableRegistry} lit des CSV du classpath
 * ({@code projects/{projectCode}/reference_tables/{CHAMP}.csv}) : c'est parfait pour le corpus de
 * développement, mais les valeurs autorisées d'un vrai projet eDoc vivent dans Documentum, pas
 * dans le JAR du moteur. L'appelant doit donc pouvoir fournir la table directement.</p>
 *
 * <p>Le contrat reprend exactement celui du registre, et la <b>règle D11 en dépend</b> :
 * renvoyer une liste vide signifie « aucune table pour ce champ », donc « rien à valider » —
 * jamais « valeur invalide ». Un champ sans table traverse la validation inchangé, en
 * {@code TO_REVIEW}.</p>
 */
@FunctionalInterface
public interface ReferenceTableSource {

    /**
     * @param targetField nom du champ cible (tel que fourni à la classification)
     * @return les valeurs officielles de ce champ, ou une <b>liste vide</b> s'il n'en existe pas.
     *         Ne doit jamais renvoyer {@code null} ni lever pour un champ simplement inconnu.
     */
    List<ReferenceEntry> tableFor(String targetField);
}
