package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Schéma d'annotation <b>ouvert</b> pour Mistral OCR : on ne nomme <em>aucun</em> champ métier
 * à l'avance. On demande uniquement le drapeau {@code cartoucheFound} et un tableau générique de
 * paires {@code {label, value}}. C'est le cœur du principe architectural d'ÉA1 : détecter et
 * extraire tout le cartouche sans présumer quels champs existent (la classification vient après).
 */
public final class CartoucheAnnotationSchema {

    /** Nom logique du schéma (champ {@code json_schema.name}). */
    public static final String NAME = "cartouche_extraction";

    /** Prompt haut niveau passé dans {@code document_annotation_prompt} pour guider l'annotation. */
    public static final String PROMPT = """
            Localise le bloc d'identification (le « cartouche ») du document. \
            C'est en général un bloc encadré, situé en bas à droite ou sur un côté, qui regroupe \
            les métadonnées du document : numéro/référence, titre, auteur ou émetteur, phase, \
            échelle, indice/révision, date, format, etc. \
            Recopie CHAQUE paire libellé/valeur exactement telle qu'imprimée : ne traduis pas, \
            n'interprète pas, ne corrige pas, n'invente aucune paire. Si un libellé n'a pas de \
            valeur, mets une chaîne vide. Si le document ne contient aucun cartouche, renvoie \
            cartoucheFound=false et une liste fields vide.""";

    private CartoucheAnnotationSchema() {
    }

    /** Le schéma JSON pur (valeur de {@code json_schema.schema}). */
    public static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");

        ObjectNode props = root.putObject("properties");

        ObjectNode found = props.putObject("cartoucheFound");
        found.put("type", "boolean");
        found.put("description", "true si un cartouche a été localisé sur le document, false sinon.");

        ObjectNode fields = props.putObject("fields");
        fields.put("type", "array");
        fields.put("description",
                "Toutes les paires libellé/valeur lues dans le cartouche, telles qu'imprimées.");

        ObjectNode item = fields.putObject("items");
        item.put("type", "object");
        ObjectNode itemProps = item.putObject("properties");

        ObjectNode label = itemProps.putObject("label");
        label.put("type", "string");
        label.put("description", "Le libellé imprimé, recopié tel quel (ex. « N° de plan », « Echelle »).");

        ObjectNode value = itemProps.putObject("value");
        value.put("type", "string");
        value.put("description", "La valeur associée, recopiée telle quelle. Chaîne vide si absente.");

        item.putArray("required").add("label").add("value");
        item.put("additionalProperties", false);

        root.putArray("required").add("cartoucheFound").add("fields");
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
