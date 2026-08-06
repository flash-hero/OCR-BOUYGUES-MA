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
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * Charge les variables d'un fichier {@code .env} (à la racine du projet) dans l'environnement Spring,
 * <b>avec une priorité inférieure</b> aux vraies variables d'environnement : si {@code OCR_API_KEY}
 * est déjà défini dans le système, il l'emporte sur le {@code .env}.
 *
 * <p>Permet d'écrire {@code ${OCR_API_KEY}} dans application.yml sans dépendance externe.
 * Le fichier {@code .env} est optionnel : son absence n'est pas une erreur (c'est {@code check_setup}
 * qui diagnostique l'absence de clé).</p>
 *
 * <p><b>Les valeurs ne sont jamais recopiées dans les propriétés système de la JVM</b>
 * ({@code System.setProperty}), et c'est délibéré : la source de propriétés ci-dessous suffit à
 * résoudre les {@code ${...}}, alors qu'un export global aurait trois défauts, tous constatés en
 * test avant correction — il écrit la clé API dans un état global lisible par tout le processus ;
 * il <i>inverse</i> la priorité voulue, car {@code systemProperties} prime sur
 * {@code systemEnvironment} et la valeur du fichier finissait par battre une vraie variable
 * d'environnement ; et il rend le post-processeur non idempotent, le second passage retrouvant sa
 * propre valeur et n'ajoutant plus aucune source.</p>
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "dotenvFile";
    private static final String DEFAULT_FILE = ".env";

    private final Path envFile;

    /** Constructeur utilisé par Spring (déclaré dans {@code META-INF/spring.factories}). */
    public DotenvEnvironmentPostProcessor() {
        this(Path.of(DEFAULT_FILE));
    }

    /**
     * Visible pour test : permet de pointer un {@code .env} temporaire. Sans ce point d'entrée, un
     * test de {@link #postProcessEnvironment} lirait le {@code .env} réel du poste (avec la vraie
     * clé), ce qui rendrait le test dépendant de la machine — c'est la raison pour laquelle cette
     * méthode n'avait aucune couverture jusqu'ici.
     */
    DotenvEnvironmentPostProcessor(Path envFile) {
        this.envFile = envFile;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
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
            }
        }
        if (effectiveValues.isEmpty()) {
            return;
        }
        addBelowRealEnvironmentVariables(environment.getPropertySources(),
                new MapPropertySource(SOURCE_NAME, effectiveValues));
    }

    /**
     * Insère le {@code .env} juste <b>après</b> les vraies variables d'environnement, donc à une
     * priorité inférieure (voir le contrat en tête de classe).
     *
     * <p>Le repli sur {@code addLast} n'est pas décoratif : {@code addAfter} lève
     * {@code IllegalArgumentException} si la source nommée est absente, ce qui ferait échouer le
     * démarrage sur tout environnement qui n'expose pas {@code systemEnvironment} (un
     * {@code MockEnvironment} de test, par exemple). Le rang visé — sous les vraies variables —
     * est de toute façon celui qu'obtient {@code addLast} quand cette source n'existe pas.</p>
     */
    private static void addBelowRealEnvironmentVariables(MutablePropertySources sources, MapPropertySource dotenv) {
        String realEnvironmentVariables = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        if (sources.contains(realEnvironmentVariables)) {
            sources.addAfter(realEnvironmentVariables, dotenv);
        } else {
            sources.addLast(dotenv);
        }
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
