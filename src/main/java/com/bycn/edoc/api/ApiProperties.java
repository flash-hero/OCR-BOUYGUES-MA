package com.bycn.edoc.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de l'API d'extraction (préfixe {@code edoc.api}).
 *
 * @param key             clé partagée exigée dans l'en-tête {@code X-Api-Key}. Injectée depuis
 *                        {@code EDOC_API_KEY}, jamais écrite dans le dépôt. <b>Vide = aucune
 *                        authentification</b> : commode en développement local, à ne jamais laisser
 *                        ainsi dès que le service est joignable par d'autres postes (un
 *                        avertissement est journalisé au démarrage).
 * @param enrichSynonyms  autorise {@link SynonymEnricher} à compléter les libellés de l'appelant
 *                        avec ceux de {@code schema_fields.yaml}. Heuristique <b>non mesurée</b> :
 *                        désactivable sans toucher au code.
 */
@ConfigurationProperties(prefix = "edoc.api")
public record ApiProperties(
        String key,
        @DefaultValue("true") boolean enrichSynonyms
) {

    public boolean hasKey() {
        return key != null && !key.isBlank();
    }
}
