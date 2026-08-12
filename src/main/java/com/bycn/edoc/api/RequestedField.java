package com.bycn.edoc.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Un champ que l'appelant veut voir rempli, décrit entièrement dans la requête.
 *
 * <p>C'est la clé de la généricité : le moteur ne connaît <em>aucun</em> champ à l'avance. Chaque
 * projet eDoc configure sa propre liste ({@code Project.defSpecPlan}) avec ses propres noms
 * techniques et ses propres libellés — le moteur se contente de ce qu'on lui décrit ici.</p>
 *
 * @param name          nom technique du champ, renvoyé tel quel dans la réponse (ex. {@code spec_char1}).
 *                      Jamais interprété : c'est la clé de correspondance pour l'appelant.
 * @param labels        libellés sous lesquels le champ peut apparaître sur le document
 *                      (ex. {@code ["Phase", "Pha"]} — longLabel et shortLabel côté eDoc).
 *                      Comparés en correspondance <b>floue</b>, jamais en égalité stricte.
 * @param allowedValues valeurs officielles du champ. Liste vide = champ libre : la valeur lue est
 *                      renvoyée telle quelle en {@code TO_REVIEW}, jamais rejetée (règle D11).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestedField(String name, List<String> labels, List<AllowedValue> allowedValues) {

    public RequestedField {
        labels = (labels == null) ? List.of() : List.copyOf(labels);
        allowedValues = (allowedValues == null) ? List.of() : List.copyOf(allowedValues);
    }
}
