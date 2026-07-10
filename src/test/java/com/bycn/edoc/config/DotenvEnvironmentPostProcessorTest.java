package com.bycn.edoc.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotenvEnvironmentPostProcessorTest {

    @Test
    void parses_pairs_ignoring_comments_blanks_export_prefix_and_surrounding_quotes(@TempDir Path tmp)
            throws Exception {
        Path env = tmp.resolve(".env");
        Files.writeString(env, """
                # un commentaire
                MISTRAL_API_KEY=abc123

                export FOO="bar baz"
                EMPTY=
                QUOTED='q'
                """);

        Map<String, Object> values = new DotenvEnvironmentPostProcessor().parse(env);

        assertThat(values).containsEntry("MISTRAL_API_KEY", "abc123");
        assertThat(values).containsEntry("FOO", "bar baz");
        assertThat(values).containsEntry("EMPTY", "");
        assertThat(values).containsEntry("QUOTED", "q");
        assertThat(values).doesNotContainKey("# un commentaire");
    }
}
