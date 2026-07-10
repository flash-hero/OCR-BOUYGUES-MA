package com.bycn.edoc.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Charge les variables d'un fichier {@code .env} (à la racine du projet) dans l'environnement Spring,
 * <b>avec une priorité inférieure</b> aux vraies variables d'environnement : si {@code MISTRAL_API_KEY}
 * est déjà défini dans le système, il l'emporte sur le {@code .env}.
 *
 * <p>Permet d'écrire {@code ${MISTRAL_API_KEY}} dans application.yml sans dépendance externe.
 * Le fichier {@code .env} est optionnel : son absence n'est pas une erreur (c'est {@code check_setup}
 * qui diagnostique l'absence de clé).</p>
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "dotenvFile";
    private static final String DEFAULT_FILE = ".env";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = Path.of(DEFAULT_FILE);
        if (!Files.isRegularFile(envFile)) {
            return;
        }
        Map<String, Object> values = parse(envFile);
        if (values.isEmpty()) {
            return;
        }
        Map<String, Object> effectiveValues = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (environment.getProperty(entry.getKey()) == null) {
                effectiveValues.put(entry.getKey(), entry.getValue());
                if (System.getProperty(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
        if (effectiveValues.isEmpty()) {
            return;
        }
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, effectiveValues));
    }

    /** Parse un fichier {@code .env} en couples clé/valeur (visible pour test). */
    Map<String, Object> parse(Path envFile) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return values; // fichier illisible : traité comme absent
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = stripQuotes(line.substring(eq + 1).strip());
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private String stripQuotes(String v) {
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
