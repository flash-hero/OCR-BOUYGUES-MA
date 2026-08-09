package com.bycn.edoc.ocr;

import java.util.List;

/**
 * Un champ du formulaire que l'appelant souhaite voir <b>rattaché</b> dès la lecture : son nom et
 * les libellés sous lesquels il peut apparaître sur le papier.
 *
 * <p>Type volontairement local au paquet {@code ocr} (et non {@code classification.TargetField}) :
 * l'extraction reste la couche basse, elle ne dépend d'aucune couche au-dessus d'elle. La lecture
 * reste par ailleurs <b>générique</b> — toutes les paires sont toujours renvoyées, ces cibles ne
 * font qu'ajouter une proposition de rattachement (voir {@link CartoucheAssignment}), jamais un
 * filtre.</p>
 *
 * @param name   nom du champ côté formulaire (ex. {@code numero}, {@code spec_char2})
 * @param labels libellés possibles de ce champ sur le papier (ex. « Numéro », « N° », « Num »)
 */
public record ReadTarget(String name, List<String> labels) {

    public ReadTarget {
        labels = (labels == null) ? List.of() : List.copyOf(labels);
    }
}
