package com.bycn.edoc.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Tables de référence par projet eDoc, chargées depuis le classpath.
 *
 * <p><b>C'est la présence du fichier qui décide, pas le code.</b> Aucune liste de champs
 * « validables » n'est écrite en Java : si
 * {@code projects/{projectCode}/reference_tables/{TARGET_FIELD}.csv} existe, le champ est validé
 * contre son contenu ; sinon {@link #getTable} renvoie une liste vide et le champ traverse P4
 * inchangé. Un nouveau projet eDoc avec des champs et des tables entièrement différents s'ajoute
 * donc en déposant des fichiers, sans toucher une ligne de code.</p>
 *
 * <p>Corollaire à ne pas perdre de vue : <b>absence de table = rien à valider</b>, jamais
 * « valide par défaut ». Le champ reste {@code TO_REVIEW}.</p>
 */
public class ReferenceTableRegistry {

    static final String PATH_TEMPLATE = "projects/%s/reference_tables/%s.csv";

    private static final String COLUMN_CODE = "code";
    private static final String COLUMN_LIBELLE = "libelle";

    /**
     * {@code projectCode} et {@code targetField} viennent de l'appel API : ils sont interpolés dans
     * un chemin de ressource, donc jamais utilisés sans filtre. Tout ce qui sort de cet alphabet
     * (séparateurs, {@code ..}) est traité comme « pas de table », c'est-à-dire le comportement sûr
     * de repli, plutôt que comme une erreur.
     */
    private static final Pattern SAFE_PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    /** Les tables ne changent pas en cours de run : premier chargement puis mémoire. */
    private final Map<String, List<ReferenceEntry>> cache = new ConcurrentHashMap<>();

    /**
     * Table du champ demandé pour ce projet.
     *
     * @return les lignes du CSV, ou une <b>liste vide</b> si le fichier n'existe pas — jamais
     *         d'exception, l'absence de table est un cas nominal (champ sans vocabulaire connu).
     * @throws IllegalStateException si le fichier existe mais est illisible ou malformé (erreur de
     *         configuration réelle, à ne pas masquer en pass-through silencieux).
     */
    public List<ReferenceEntry> getTable(String projectCode, String targetField) {
        if (!isSafe(projectCode) || !isSafe(targetField)) {
            return List.of();
        }
        return cache.computeIfAbsent(projectCode + "/" + targetField, key -> load(projectCode, targetField));
    }

    private static boolean isSafe(String segment) {
        return segment != null && SAFE_PATH_SEGMENT.matcher(segment).matches();
    }

    private static List<ReferenceEntry> load(String projectCode, String targetField) {
        String resource = PATH_TEMPLATE.formatted(projectCode, targetField);
        try (InputStream in = ReferenceTableRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return List.of();   // pas de table pour ce champ : cas nominal, pas une erreur
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture impossible de " + resource, e);
        }
    }

    static List<ReferenceEntry> parse(String content, String resource) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();
        List<ReferenceEntry> entries = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(stripByteOrderMark(content), format)) {
            for (CSVRecord record : parser) {
                if (!record.isMapped(COLUMN_CODE) || !record.isMapped(COLUMN_LIBELLE)) {
                    throw new IllegalStateException(
                            resource + " : colonnes « code » et « libelle » attendues, en-tête lu = " + parser.getHeaderNames());
                }
                String code = record.get(COLUMN_CODE);
                if (code == null || code.isBlank()) {
                    continue;   // ligne sans code : rien à comparer, on l'ignore
                }
                entries.add(new ReferenceEntry(code, record.get(COLUMN_LIBELLE)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("CSV illisible : " + resource, e);
        }
        return List.copyOf(entries);
    }

    /** Un BOM UTF-8 collerait au premier nom de colonne et casserait la lecture de « code ». */
    private static String stripByteOrderMark(String content) {
        return content.startsWith("﻿") ? content.substring(1) : content;
    }
}
