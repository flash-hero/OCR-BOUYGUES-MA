package com.bycn.edoc.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Corps de la demande d'extraction (partie {@code request} du multipart, le document étant la
 * partie {@code file}).
 *
 * <p>Tout ce dont le moteur a besoin est ici : il ne consulte aucune configuration propre au projet
 * appelant. C'est l'application stricte du principe de {@code instruction.md} — « la liste des
 * champs requis et le code du projet eDoc viennent de l'appel API, jamais déduits du document ».</p>
 *
 * @param projectCode code du projet eDoc concerné. Purement informatif ici (traçabilité / logs) :
 *                    les valeurs officielles sont portées par {@link RequestedField#allowedValues()},
 *                    pas retrouvées à partir de ce code.
 * @param fields      les champs à remplir. Liste vide = rien à classer : l'extraction brute est
 *                    quand même renvoyée dans {@code unclassifiedPairs}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionRequest(String projectCode, List<RequestedField> fields) {

    public ExtractionRequest {
        fields = (fields == null) ? List.of() : List.copyOf(fields);
    }
}
