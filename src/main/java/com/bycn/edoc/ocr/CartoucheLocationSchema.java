package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Schéma de la <b>passe 1</b> : localisation du cartouche sur la page.
 *
 * <p>On ne demande <em>aucun</em> contenu, seulement <b>où</b> est le cartouche : sa boîte
 * englobante (fractions de l'image envoyée), une zone grossière pour les journaux, et la rotation à
 * appliquer pour que son texte soit droit. Le principe : une position reste lisible même après le
 * redimensionnement agressif qu'un modèle applique à une image de 2&nbsp;mètres, là où le texte fin
 * du cartouche, lui, devient illisible.</p>
 *
 * <p>On ne présume <b>pas</b> une position fixe (ex. toujours bas-droite) : sur ce corpus, des
 * cartouches ont été observés dans plusieurs coins différents, le long d'un bord entier, et à
 * mi-hauteur d'une feuille à moitié vide. C'est le modèle qui situe la boîte à chaque document —
 * c'est précisément ce qui permet d'atteindre un cartouche qu'aucun découpage de coin fixe
 * n'atteint (défaut historique de 12.pdf, grille d'identification à ~48&nbsp;% de la hauteur).</p>
 */
public final class CartoucheLocationSchema {

    /** Nom logique du schéma (champ {@code json_schema.name}). */
    public static final String NAME = "cartouche_location";

    /** Zones possibles (grille 3×3 + {@code unknown}) — pour les journaux et le repli par coins. */
    public static final String[] ZONES = {
        "top-left", "top-center", "top-right",
        "left", "center", "right",
        "bottom-left", "bottom-center", "bottom-right",
        "unknown"
    };

    /**
     * Rotations possibles à appliquer à l'image du cartouche pour que son texte soit droit.
     * Exprimées comme l'<b>action corrective</b> (et non l'état constaté) : aucune ambiguïté sur le
     * sens, on applique littéralement ce que le modèle répond.
     */
    public static final String[] ROTATIONS = {"none", "90-cw", "90-ccw", "180"};

    /** Prompt de la passe 1 : demande une position précise, pas un contenu. */
    public static final String PROMPT = """
            Ce document est un plan ou document technique. Repère le « cartouche » : le bloc qui \
            porte l'IDENTITÉ PROPRE de ce document — son numéro, son indice/révision, son titre, sa \
            phase, son émetteur, son lot, sa date, son échelle, son format. C'est une BOÎTE À \
            BORDURES faite de PLUSIEURS lignes courtes de type formulaire, le plus souvent accolée \
            à un BORD ou nichée dans un COIN de la feuille (parfois une bande le long d'un bord \
            entier), et elle se distingue nettement du dessin. \
            PIÈGES À ÉVITER — ces blocs ressemblent à un cartouche mais n'en sont PAS un : \
            l'ANNUAIRE DES INTERVENANTS (une liste de sociétés — maître d'ouvrage, architecte, \
            bureaux d'études, contrôle technique — avec leurs adresses, téléphones, fax et e-mails : \
            il décrit d'AUTRES sociétés, pas ce document) ; une LÉGENDE de symboles ; un TABLEAU DE \
            RÉVISIONS seul ; le grand titre du plan ; une simple phrase descriptive. \
            Si plusieurs blocs sont accolés le long du même bord, choisis celui qui contient le \
            NUMÉRO et l'INDICE du document — c'est lui le cartouche, même s'il est plus petit ou \
            plus discret que ses voisins. \
            NE LIS PAS son contenu en détail. Donne : \
            1. box : la boîte englobante du cartouche, en fractions de l'image entre 0 et 1 — \
            left/top = coin haut-gauche, right/bottom = coin bas-droit. Deux exigences opposées, \
            à tenir ensemble : elle doit contenir la TOTALITÉ du cartouche, bordures comprises — \
            une boîte qui coupe ne serait-ce qu'une colonne ou une ligne fait échouer toute la \
            lecture — mais elle doit s'ARRÊTER LÀ. N'y inclus pas les blocs voisins accolés \
            (annuaire des intervenants, légende, tableau de révisions, vignette du projet, dessin) : \
            chaque bloc voisin avalé ralentit la lecture et fait remonter des informations qui ne \
            concernent pas ce document. Serre au plus près du cartouche, sans jamais l'entamer ; \
            2. corner : sa zone approximative parmi la liste ; \
            3. rotation : la rotation à appliquer à l'image pour que le TEXTE du cartouche soit \
            droit et lisible (none s'il l'est déjà, 90-cw = tourner l'image de 90° horaire, \
            90-ccw = 90° antihoraire, 180 = retourner). Regarde le sens des lettres du cartouche \
            lui-même, pas celui du reste de la feuille ; \
            4. cartoucheFound : true si une telle boîte est visible, false sinon (alors box à 0 et \
            corner unknown). \
            Réponds directement par le JSON demandé, sans réfléchir et sans un mot d'explication.""";

    /**
     * Le prompt de localisation, complété — quand l'appel précise ses champs — de la liste des
     * libellés attendus.
     *
     * <p>C'est l'information la plus discriminante dont on dispose : parmi plusieurs blocs accolés
     * au même bord, le cartouche est celui qui porte <em>ces</em> libellés-là. Elle vient
     * entièrement de la requête (le moteur ne connaît aucun champ à l'avance), donc le principe
     * générique est intact : on ne fige rien, on transmet ce que l'appelant a déclaré.</p>
     */
    public static String promptFor(java.util.List<ReadTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return PROMPT;
        }
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        for (ReadTarget target : targets) {
            for (String label : target.labels()) {
                if (label != null && !label.isBlank()) {
                    labels.add(label.trim());
                }
            }
        }
        if (labels.isEmpty()) {
            return PROMPT;
        }
        return PROMPT + " INDICE DÉCISIF — le bloc recherché contient normalement tout ou partie de "
                + "ces libellés : " + String.join(", ", labels)
                + ". Le bloc qui en contient le plus est le cartouche.";
    }

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

        ObjectNode box = props.putObject("box");
        box.put("type", "object");
        box.put("description",
                "Boîte englobante du cartouche complet, en fractions de l'image (0 à 1).");
        ObjectNode boxProps = box.putObject("properties");
        for (String side : new String[] {"left", "top", "right", "bottom"}) {
            ObjectNode n = boxProps.putObject(side);
            n.put("type", "number");
            n.put("description", "Fraction de l'image entre 0 et 1.");
        }
        box.putArray("required").add("left").add("top").add("right").add("bottom");
        box.put("additionalProperties", false);

        ObjectNode corner = props.putObject("corner");
        corner.put("type", "string");
        ArrayNode en = corner.putArray("enum");
        for (String zone : ZONES) {
            en.add(zone);
        }
        corner.put("description", "Zone approximative du cartouche sur la page (une seule valeur).");

        ObjectNode rotation = props.putObject("rotation");
        rotation.put("type", "string");
        ArrayNode rot = rotation.putArray("enum");
        for (String r : ROTATIONS) {
            rot.add(r);
        }
        rotation.put("description",
                "Rotation à appliquer à l'image pour que le texte du cartouche soit droit.");

        root.putArray("required").add("cartoucheFound").add("box").add("corner").add("rotation");
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
