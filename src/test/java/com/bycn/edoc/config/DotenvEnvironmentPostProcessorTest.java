package com.bycn.edoc.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class DotenvEnvironmentPostProcessorTest {

    private static final String SYSTEM_ENVIRONMENT = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

    private static Path dotenv(Path dir, String content) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, content);
        return env;
    }

    /** Environnement standard dont les « vraies » variables d'environnement sont simulees. */
    private static StandardEnvironment environmentWithRealVars(Map<String, Object> osVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(SYSTEM_ENVIRONMENT,
                new SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT, osVariables));
        return environment;
    }

    private static void run(Path envFile, StandardEnvironment environment) {
        new DotenvEnvironmentPostProcessor(envFile).postProcessEnvironment(environment, null);
    }

    private static List<String> sourceNames(StandardEnvironment environment) {
        return environment.getPropertySources().stream().map(PropertySource::getName).toList();
    }

    @Test
    void parses_pairs_ignoring_comments_blanks_export_prefix_and_surrounding_quotes(@TempDir Path tmp)
            throws Exception {
        Path env = dotenv(tmp, """
                # un commentaire
                OCR_API_KEY=abc123

                export FOO="bar baz"
                EMPTY=
                QUOTED='q'
                """);

        Map<String, Object> values = new DotenvEnvironmentPostProcessor().parse(env);

        assertThat(values).containsEntry("OCR_API_KEY", "abc123");
        assertThat(values).containsEntry("FOO", "bar baz");
        assertThat(values).containsEntry("EMPTY", "");
        assertThat(values).containsEntry("QUOTED", "q");
        assertThat(values).doesNotContainKey("# un commentaire");
    }

    @Test
    void a_dotenv_value_becomes_resolvable_as_a_placeholder() {
        // La raison d'etre de la classe : ${OCR_API_KEY} dans application.yml doit se resoudre.
        StandardEnvironment environment = environmentWithRealVars(Map.of());

        runWithTempFile(environment, "OCR_API_KEY=depuis-le-fichier");

        assertThat(environment.getProperty("OCR_API_KEY")).isEqualTo("depuis-le-fichier");
        assertThat(environment.resolvePlaceholders("${OCR_API_KEY}")).isEqualTo("depuis-le-fichier");
    }

    @Test
    void a_real_environment_variable_wins_over_the_dotenv_file() {
        // Contrat documente dans le javadoc de la classe.
        StandardEnvironment environment = environmentWithRealVars(Map.of("OCR_API_KEY", "depuis-l-os"));

        runWithTempFile(environment, "OCR_API_KEY=depuis-le-fichier");

        assertThat(environment.getProperty("OCR_API_KEY")).isEqualTo("depuis-l-os");
    }

    @Test
    void the_dotenv_source_is_ranked_below_the_real_environment_variables() {
        // Le filtre en amont suffit aujourd'hui, mais le rang doit aussi porter l'intention :
        // une source .env placee en tete primerait sur les vraies variables.
        StandardEnvironment environment = environmentWithRealVars(Map.of("AUTRE_VAR", "x"));

        runWithTempFile(environment, "OCR_API_KEY=depuis-le-fichier");

        List<String> names = sourceNames(environment);
        assertThat(names).contains("dotenvFile");
        assertThat(names.indexOf("dotenvFile")).isGreaterThan(names.indexOf(SYSTEM_ENVIRONMENT));
    }

    @Test
    void an_environment_without_a_system_environment_source_does_not_fail(@TempDir Path tmp) throws Exception {
        // MockEnvironment n'a pas de source « systemEnvironment » : addAfter() sur un nom absent
        // leve IllegalArgumentException et ferait echouer le demarrage.
        Path env = dotenv(tmp, "OCR_API_KEY=depuis-le-fichier");
        MockEnvironment environment = new MockEnvironment();

        assertThatCode(() -> new DotenvEnvironmentPostProcessor(env).postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
        assertThat(environment.getProperty("OCR_API_KEY")).isEqualTo("depuis-le-fichier");
    }

    @Test
    void a_missing_dotenv_file_is_not_an_error(@TempDir Path tmp) {
        StandardEnvironment environment = environmentWithRealVars(Map.of());

        assertThatCode(() -> run(tmp.resolve("absent"), environment)).doesNotThrowAnyException();
        assertThat(sourceNames(environment)).doesNotContain("dotenvFile");
    }

    @Test
    void dotenv_values_are_never_written_into_jvm_wide_system_properties() {
        // Un EnvironmentPostProcessor ne doit pas exporter une cle API dans l'etat global de la JVM :
        // c'est inutile (la source de proprietes suffit, voir le test de placeholder ci-dessus) et
        // systemProperties prime sur systemEnvironment, ce qui contredirait le rang voulu.
        StandardEnvironment environment = environmentWithRealVars(Map.of());
        String key = "DOTENV_TEST_KEY_" + System.nanoTime();
        try {
            runWithTempFile(environment, key + "=une-valeur-sensible");

            assertThat(environment.getProperty(key)).isEqualTo("une-valeur-sensible");
            assertThat(System.getProperty(key)).isNull();
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void running_twice_in_the_same_jvm_yields_the_same_result(@TempDir Path tmp) throws Exception {
        // Sans effet de bord global, le post-processeur est idempotent : deux environnements
        // successifs voient la meme chose.
        Path env = dotenv(tmp, "OCR_API_KEY=depuis-le-fichier");

        StandardEnvironment first = environmentWithRealVars(Map.of());
        run(env, first);
        StandardEnvironment second = environmentWithRealVars(Map.of());
        run(env, second);

        assertThat(first.getProperty("OCR_API_KEY")).isEqualTo("depuis-le-fichier");
        assertThat(second.getProperty("OCR_API_KEY")).isEqualTo("depuis-le-fichier");
        assertThat(sourceNames(second)).contains("dotenvFile");
    }

    @Test
    void an_already_defined_property_is_not_duplicated_into_the_dotenv_source() {
        StandardEnvironment environment = environmentWithRealVars(Map.of("OCR_API_KEY", "depuis-l-os"));
        environment.getPropertySources().addLast(new MapPropertySource("autre", Map.of("DEJA", "la")));

        runWithTempFile(environment, "OCR_API_KEY=ignore\nNOUVELLE=ajoutee");

        MapPropertySource dotenv = (MapPropertySource) environment.getPropertySources().get("dotenvFile");
        assertThat(dotenv).isNotNull();
        assertThat(dotenv.getSource()).containsOnlyKeys("NOUVELLE");
    }

    /** Ecrit un .env temporaire puis lance le post-processeur dessus. */
    private static void runWithTempFile(StandardEnvironment environment, String content) {
        try {
            Path tmp = Files.createTempDirectory("dotenv-test");
            Path env = tmp.resolve(".env");
            Files.writeString(env, content);
            run(env, environment);
            Files.deleteIfExists(env);
            Files.deleteIfExists(tmp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
