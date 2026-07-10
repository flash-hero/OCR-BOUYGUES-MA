package com.bycn.edoc.ocr;

/**
 * Une paire libellé/valeur lue telle quelle dans le cartouche, sans interprétation
 * ni traduction. Aucun sens métier n'est attribué ici : la classification vient plus tard,
 * dans une session ultérieure (hors périmètre ÉA1).
 *
 * @param label le libellé imprimé (ex. « N° de plan », « Echelle », « Indice »)
 * @param value la valeur associée, recopiée telle quelle (chaîne vide si absente)
 */
public record CartoucheField(String label, String value) {
}
