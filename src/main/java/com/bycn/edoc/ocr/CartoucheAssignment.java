package com.bycn.edoc.ocr;

/**
 * Proposition de rattachement faite par le modèle à la lecture : « cette paire lue correspond à ce
 * champ du formulaire ». Le libellé imprimé peut être très différent du nom du champ (abrégé,
 * composé — « NUMERO DE DOCUMENT » pour « Numéro » —, ou dans une autre langue) : c'est justement ce
 * qu'une distance de chaînes ne sait pas trancher et qu'un modèle de langage sait.
 *
 * <p>Une proposition n'est jamais crue sur parole : le service la <b>contrôle</b> — la valeur doit
 * exister parmi les paires réellement lues, sinon elle est ignorée et le champ repasse par la
 * classification floue classique (voir {@code ExtractionService}).</p>
 *
 * @param field le nom du champ demandé (contraint par le schéma à la liste des champs de l'appel)
 * @param label le libellé imprimé de la paire choisie, tel que lu
 * @param value la valeur de la paire choisie, telle que lue
 */
public record CartoucheAssignment(String field, String label, String value) {
}
