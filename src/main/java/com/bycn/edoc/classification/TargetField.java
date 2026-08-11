package com.bycn.edoc.classification;

import java.util.List;

/**
 * Un champ cible à remplir, accompagné des libellés sous lesquels il peut apparaître
 * dans un cartouche.
 *
 * <p><b>Pourquoi ce record existe.</b> Jusqu'ici, les synonymes d'un champ venaient
 * exclusivement de {@code schema_fields.yaml}, retrouvés par une recherche <em>exacte</em>
 * sur le nom du champ ({@link SchemaFieldsRegistry#find}). Cela suppose que tout appelant
 * nomme ses champs comme le yaml — vrai pour le corpus de test, faux pour eDoc : chaque
 * projet eDoc configure ses propres champs, aux noms arbitraires
 * ({@code spec_char1}...), avec leurs propres libellés d'affichage.</p>
 *
 * <p>Ce record permet donc à l'appelant de fournir <b>directement</b> le couple
 * (nom du champ, libellés attendus), sans passer par le référentiel statique. Le yaml
 * reste utilisé quand l'appelant ne fournit que des noms de champs (chemin historique,
 * inchangé) et peut aussi <em>enrichir</em> des libellés fournis — voir la couche API.</p>
 *
 * @param name     nom du champ tel qu'il devra être renvoyé à l'appelant (jamais interprété)
 * @param synonyms libellés à comparer, en correspondance floue, au libellé lu sur le
 *                 document. Liste vide = aucun candidat possible, le champ sortira
 *                 {@link FieldStatus#MISSING} — jamais une erreur.
 */
public record TargetField(String name, List<String> synonyms) {

    public TargetField {
        synonyms = (synonyms == null) ? List.of() : List.copyOf(synonyms);
    }
}
