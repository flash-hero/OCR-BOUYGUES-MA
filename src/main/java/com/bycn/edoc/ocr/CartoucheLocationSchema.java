package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Schéma de la <b>passe 1</b> : localisation grossière du cartouche sur un grand plan.
 *
 * <p>On ne demande <em>aucun</em> contenu, seulement une <b>zone</b> approximative parmi une liste
 * fermée ({@link #ZONES}). Le principe : une estimation de position grossière reste lisible même
 * après le redimensionnement agressif que Mistral applique à une image de 2&nbsp;mètres, là où le
 * texte fin du cartouche, lui, devient illisible.</p>
 *
 * <p>On ne présume <b>pas</b> une position fixe (ex. toujours bas-droite) : sur ce corpus, des
 * cartouches ont été observés dans plusieurs coins différents. C'est le modèle qui situe la zone à
 * chaque document.</p>
 */
public final class CartoucheLocationSchema {

    /** Nom logique du schéma (champ {@code json_schema.name}). */
    public static final String NAME = "cartouche_location";

    /** Zones possibles renvoyées par la passe 1 (grille 3×3 + {@code unknown}). */
    public static final String[] ZONES = {
        "top-left", "top-center", "top-right",
        "left", "center", "right",
        "bottom-left", "bottom-center", "bottom-right",
        "unknown"
    };

    /** Prompt de la passe 1 : demande une zone, pas un contenu — et vise la boîte, pas le titre. */
    public static final String PROMPT = """
            Ce document est un grand plan technique. Repère le « cartouche » : c'est une petite \
            BOÎTE À BORDURES qui regroupe PLUSIEURS lignes courtes de type formulaire — des paires \
            libellé/valeur : numéro/référence, indice/révision, émetteur, lot, phase, date, échelle, \
            format, numéro de document… Elle est le plus souvent accolée à un BORD ou nichée dans un \
            COIN de la feuille, et se distingue nettement du dessin. \
            NE CHOISIS PAS le grand titre du plan, ni un titre de tableau, ni un en-tête : ce n'est \
            PAS le cartouche. Le cartouche est un bloc DENSE de codes courts, jamais une simple \
            phrase descriptive. \
            NE LIS PAS son contenu : indique seulement sa ZONE approximative parmi la liste. \
            Réponds « unknown » uniquement si aucune boîte de ce type n'est identifiable. Renseigne \
            aussi cartoucheFound (true si une telle boîte est visible, false sinon).""";

    private CartoucheLocationSchema() {
    }

    /** Le schéma JSON pur (valeur de {@code json_schema.schema}). */
    public static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");

        ObjectNode props = root.putObject("properties");

        ObjectNode found = props.putObject("cartoucheFound");
        found.put("type", "boolean");
        found.put("description", "true si un cartouche est visible sur la page, false sinon.");

        ObjectNode corner = props.putObject("corner");
        corner.put("type", "string");
        ArrayNode en = corner.putArray("enum");
        for (String zone : ZONES) {
            en.add(zone);
        }
        corner.put("description", "Zone approximative du cartouche sur la page (une seule valeur).");

        root.putArray("required").add("cartoucheFound").add("corner");
        root.put("additionalProperties", false);
        return root;
    }

    /** L'objet complet à placer dans le champ {@code document_annotation_format} de la requête. */
    public static ObjectNode format(ObjectMapper mapper) {
        ObjectNode fmt = mapper.createObjectNode();
        fmt.put("type", "json_schema");
        ObjectNode js = fmt.putObject("json_schema");
        js.put("name", NAME);
        js.set("schema", schema(mapper));
        js.put("strict", true);
        return fmt;
    }
}
