package com.bycn.edoc.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Schéma d'annotation <b>ouvert</b> : on ne nomme <em>aucun</em> champ métier à l'avance. On demande
 * le drapeau {@code cartoucheFound} et un tableau générique de paires {@code {label, value}}. C'est
 * le cœur du principe architectural d'ÉA1 : détecter et extraire tout le cartouche sans présumer
 * quels champs existent.
 *
 * <p>Quand l'appel API fournit ses champs cibles ({@link ReadTarget}), le schéma s'enrichit d'un
 * tableau {@code assignments} : le modèle propose, pour chaque champ demandé, la paire lue qui lui
 * correspond. La liste des noms de champs est <b>contrainte par enum</b> dans le schéma — le modèle
 * ne peut pas inventer un champ — et chaque proposition est re-contrôlée côté serveur (la valeur
 * doit exister parmi les paires lues). Le contrat générique ne change pas : {@code fields} reste la
 * lecture complète, jamais filtrée.</p>
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
            Recopie CHAQUE paire libellé/valeur exactement telle qu'imprimée, CARACTÈRE PAR \
            CARACTÈRE : ne traduis pas (garde ÉMETTEUR, NIVEAU, N°… tels quels — jamais « ISSUER », \
            « LEVEL », « NUMBER »), n'interprète pas, ne corrige pas, n'invente aucune paire. \
            Recopie chaque valeur EN ENTIER, y compris quand elle s'étale sur plusieurs lignes \
            sous son libellé : toutes les lignes de la valeur, pas seulement la première. \
            Lis attentivement les petits libellés, même en caractères minuscules ou stylisés. \
            IMPORTANT — tableaux à deux lignes : le cartouche contient souvent un petit tableau où \
            une ligne d'EN-TÊTES (ex. Phase | Émetteur | Lot | Zone | Bâtiment | Type | Niveau | \
            N° document | Indice) est placée juste AU-DESSUS d'une ligne de VALEURS (ex. EXE | CPI | \
            003 | TZ | AB | MET | TN | 0114 | G). Associe alors chaque en-tête à la valeur située \
            directement en dessous (ou dans la même colonne), et renvoie une paire libellé=en-tête, \
            valeur=cellule correspondante — jamais des libellés à valeur vide d'un côté et des \
            valeurs orphelines de l'autre. \
            Tableau de révisions (liste datée des indices A, B, C… avec leurs modifications) : ne \
            renvoie PAS ses lignes une à une ; seules les informations d'identification du document \
            comptent. \
            Coordonnées des intervenants (adresses postales, codes postaux, téléphones, fax, \
            e-mails) : ce ne sont pas des champs d'identification du document, ne les renvoie pas — \
            le nom et le rôle d'un intervenant (ex. « ARCHITECTE : X ») restent des paires valides. \
            Si un libellé n'a réellement pas de valeur, mets une chaîne vide. Si le document ne \
            contient aucun cartouche, renvoie cartoucheFound=false et une liste fields vide. \
            Ne renvoie RIEN d'autre que les libellés et les valeurs effectivement lisibles sur \
            l'image : aucune paire déduite, devinée ou complétée de mémoire. \
            Réponds directement par le JSON demandé, sans réfléchir et sans un mot d'explication.""";

    /**
     * Consigne de lecture d'un <b>texte</b> (couche texte du PDF) plutôt que d'une image.
     *
     * <p>Le contenu est déjà exact : on ne demande donc pas de « recopier », mais de <b>trouver</b>
     * le cartouche au milieu de tout le reste — annotations de dessin, cotes, légendes, annuaire des
     * intervenants. C'est précisément le tri que le modèle sait faire et qu'une règle ne sait pas.</p>
     */
    static final String TEXT_PROMPT = """
            Voici TOUT le texte d'un plan ou document technique, extrait du fichier avec sa mise en \
            page d'origine (les colonnes et les lignes sont conservées). \
            Trouve le « cartouche » : le bloc qui porte l'IDENTITÉ PROPRE de ce document — son \
            numéro, son indice/révision, son titre, sa phase, son émetteur, son lot, sa date, son \
            échelle, son format — et renvoie ses paires libellé/valeur. \
            Le cartouche est très souvent un TABLEAU À DEUX LIGNES : une ligne d'en-têtes (ex. \
            PROJET PHASE EMETTEUR LOT ZONE NIVEAU TYPE NUMERO INDICE) suivie immédiatement d'une \
            ligne de valeurs alignées colonne par colonne (ex. HUA EXE TRA 36 EXT EX PLA 3100 E). \
            Associe alors chaque en-tête à la valeur de SA colonne, dans l'ordre, sans en décaler \
            aucune. Compte les en-têtes et les valeurs : il doit y en avoir autant. \
            NE RENVOIE PAS : les cotes et altitudes du dessin, les repères de réseaux, les légendes, \
            ni l'ANNUAIRE DES INTERVENANTS (la liste des sociétés — maître d'ouvrage, architecte, \
            bureaux d'études, contrôle technique — avec leurs adresses et téléphones : il décrit \
            d'AUTRES sociétés, pas ce document). \
            Si un même libellé apparaît plusieurs fois (ex. plusieurs DATE, plusieurs INDICE), garde \
            celui du cartouche — celui qui est aligné avec les autres libellés d'identification — et \
            non ceux d'un tableau de révisions. \
            Recopie les valeurs EXACTEMENT comme elles apparaissent, sans rien traduire ni corriger. \
            Si le texte ne contient aucun cartouche, renvoie cartoucheFound=false et fields vide. \
            Réponds directement par le JSON demandé, sans réfléchir et sans un mot d'explication.""";

    /** Le prompt texte, complété du rattachement quand l'appel précise ses champs. */
    public static String textPromptFor(List<ReadTarget> targets) {
        return withAssignments(TEXT_PROMPT, targets);
    }

    private CartoucheAnnotationSchema() {
    }

    /**
     * Le prompt de lecture, complété — quand des champs cibles sont fournis — de la consigne de
     * rattachement et de la liste des champs avec leurs libellés possibles.
     */
    public static String promptFor(List<ReadTarget> targets) {
        return withAssignments(PROMPT, targets);
    }

    /** Complète un prompt de lecture de la consigne de rattachement et de la liste des champs. */
    private static String withAssignments(String basePrompt, List<ReadTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return basePrompt;
        }
        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("""


                ENSUITE, remplis « assignments » : pour CHAQUE champ du formulaire listé ci-dessous, \
                cherche parmi les paires que tu viens de lire celle qui correspond à ce champ. Le \
                libellé imprimé peut être différent du nom du champ : abrégé (« N° » pour Numéro), \
                composé (« NUMERO DE DOCUMENT » pour Numéro, « Zone / Niveau » pour Niveau), ou dans \
                une autre langue (« LEVEL » pour Niveau). Recopie alors le libellé et la valeur \
                EXACTS de la paire choisie — les mêmes caractères que dans fields. \
                Un champ sans paire correspondante n'apparaît PAS dans assignments. INTERDIT : \
                proposer une valeur qui ne figure pas dans fields, rattacher deux champs à la même \
                paire, deviner une valeur d'après le contexte.
                Champs du formulaire :
                """);
        for (ReadTarget target : targets) {
            sb.append("- ").append(target.name());
            if (!target.labels().isEmpty()) {
                sb.append(" (libellés possibles : ").append(String.join(", ", target.labels())).append(")");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Le schéma JSON pur (valeur de {@code json_schema.schema}). */
    public static ObjectNode schema(ObjectMapper mapper) {
        return schema(mapper, List.of());
    }

    /** Le schéma JSON pur, enrichi du tableau {@code assignments} si des champs cibles sont fournis. */
    public static ObjectNode schema(ObjectMapper mapper, List<ReadTarget> targets) {
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

        ArrayNode required = root.putArray("required");
        required.add("cartoucheFound").add("fields");

        if (targets != null && !targets.isEmpty()) {
            ObjectNode assignments = props.putObject("assignments");
            assignments.put("type", "array");
            assignments.put("description",
                    "Rattachement des paires lues aux champs du formulaire demandés (au plus une "
                            + "entrée par champ, uniquement des paires présentes dans fields).");
            ObjectNode aItem = assignments.putObject("items");
            aItem.put("type", "object");
            ObjectNode aProps = aItem.putObject("properties");

            ObjectNode field = aProps.putObject("field");
            field.put("type", "string");
            ArrayNode names = field.putArray("enum");
            for (ReadTarget target : targets) {
                names.add(target.name());
            }
            field.put("description", "Le nom du champ du formulaire (liste fermée).");

            ObjectNode aLabel = aProps.putObject("label");
            aLabel.put("type", "string");
            aLabel.put("description", "Le libellé imprimé de la paire choisie, identique à fields.");

            ObjectNode aValue = aProps.putObject("value");
            aValue.put("type", "string");
            aValue.put("description", "La valeur de la paire choisie, identique à fields.");

            aItem.putArray("required").add("field").add("label").add("value");
            aItem.put("additionalProperties", false);
            required.add("assignments");
        }

        root.put("additionalProperties", false);
        return root;
    }

    /** L'objet complet à placer dans le champ {@code document_annotation_format} de la requête. */
    public static ObjectNode format(ObjectMapper mapper) {
        return format(mapper, List.of());
    }

    /** L'objet complet, enrichi du rattachement si des champs cibles sont fournis. */
    public static ObjectNode format(ObjectMapper mapper, List<ReadTarget> targets) {
        ObjectNode fmt = mapper.createObjectNode();
        fmt.put("type", "json_schema");
        ObjectNode js = fmt.putObject("json_schema");
        js.put("name", NAME);
        js.set("schema", schema(mapper, targets));
        js.put("strict", true);
        return fmt;
    }
}
