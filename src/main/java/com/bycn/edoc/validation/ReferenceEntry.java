package com.bycn.edoc.validation;

/**
 * Une ligne d'une table de référence propre à un projet eDoc
 * ({@code projects/{projectCode}/reference_tables/{TARGET_FIELD}.csv}, colonnes {@code code,libelle}).
 *
 * @param code    la valeur acceptée telle qu'imprimée dans le cartouche (ex. {@code EXE}, {@code 03})
 * @param libelle son intitulé lisible (ex. {@code Exécution}, {@code Terrassements})
 */
public record ReferenceEntry(String code, String libelle) {
}
