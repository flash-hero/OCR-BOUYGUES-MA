package com.bycn.edoc.classification;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * Bibliothèque de synonymes de libellés chargée depuis {@code schema_fields.yaml}.
 *
 * <p>Ce fichier est unique et partagé par tous les projets eDoc : ce qui varie d'un appel API à
 * l'autre, c'est le sous-ensemble de champs demandés ({@code requiredFields}), jamais le contenu
 * de la bibliothèque.</p>
 */
public class SchemaFieldsRegistry {

    public static final String DEFAULT_RESOURCE = "schema_fields.yaml";

    private final Map<String, SynonymEntry> fields;

    public SchemaFieldsRegistry(Map<String, SynonymEntry> fields) {
        Objects.requireNonNull(fields, "fields");
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** Charge la bibliothèque depuis le classpath (utilisé au démarrage Spring). */
    public static SchemaFieldsRegistry fromClasspath(String resource) {
        try (InputStream in = SchemaFieldsRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Ressource introuvable sur le classpath : " + resource);
            }
            return fromYaml(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Lecture impossible de " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static SchemaFieldsRegistry fromYaml(InputStream in) {
        Object root = new Yaml().load(in);
        if (!(root instanceof Map<?, ?> document) || !(document.get("fields") instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("schema_fields.yaml : clé racine « fields » absente ou malformée.");
        }
        Map<String, SynonymEntry> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) raw).entrySet()) {
            Map<String, Object> body = (entry.getValue() instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
            parsed.put(entry.getKey(),
                    new SynonymEntry(synonyms(body, "confirmed"), synonyms(body, "hypothesis")));
        }
        return new SchemaFieldsRegistry(parsed);
    }

    private static List<String> synonyms(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * Recherche <b>exacte</b> (jamais floue) : {@code requiredFields} vient de l'appel API et doit
     * nommer un champ de la bibliothèque au caractère près. Le flou ne s'applique qu'à la
     * comparaison libellé lu / synonyme, pas au nom du champ demandé.
     */
    public Optional<SynonymEntry> find(String targetField) {
        return Optional.ofNullable(fields.get(targetField));
    }

    public Set<String> fieldNames() {
        return fields.keySet();
    }

    public int size() {
        return fields.size();
    }
}
