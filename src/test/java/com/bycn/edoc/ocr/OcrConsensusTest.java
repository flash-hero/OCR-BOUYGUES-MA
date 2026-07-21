package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests du consensus multi-échantillons : vérifie qu'on retient un représentant robuste sans jamais
 * fusionner ni filtrer les paires (on renvoie l'indice d'un échantillon réel, tel quel).
 */
class OcrConsensusTest {

    private static CartoucheExtraction ex(boolean found, int nbFields) {
        List<CartoucheField> fields = new java.util.ArrayList<>();
        for (int i = 0; i < nbFields; i++) {
            fields.add(new CartoucheField("L" + i, "V" + i));
        }
        return new CartoucheExtraction(found, fields);
    }

    private static CartoucheLocation loc(boolean found, String corner) {
        return new CartoucheLocation(found, corner, null);
    }

    @Test
    void single_sample_is_returned_as_is() {
        assertThat(OcrConsensus.pickExtractionIndex(List.of(ex(true, 5)))).isZero();
        assertThat(OcrConsensus.pickLocationIndex(List.of(loc(true, "bottom-right")))).isZero();
    }

    @Test
    void empty_sample_list_is_rejected() {
        assertThatThrownBy(() -> OcrConsensus.pickExtractionIndex(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OcrConsensus.pickLocationIndex(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void majority_found_flag_wins_and_degenerate_empty_sample_is_ignored() {
        // 12.pdf : un echantillon a bascule found=false/0 paire ; les deux autres restent found=true.
        List<CartoucheExtraction> samples = List.of(ex(true, 9), ex(false, 0), ex(true, 11));

        int idx = OcrConsensus.pickExtractionIndex(samples);

        // Camp majoritaire = found=true {9,11} ; mediane basse = 9 (indice 0), jamais l'echantillon vide.
        assertThat(idx).isEqualTo(0);
        assertThat(samples.get(idx).cartoucheFound()).isTrue();
        assertThat(samples.get(idx).fields()).hasSize(9);
    }

    @Test
    void median_pair_count_is_chosen_over_the_over_extracted_outlier() {
        // 15.pdf : une sur-extraction (77) coexiste avec des lectures plus sobres ; on prend le milieu.
        List<CartoucheExtraction> samples = List.of(ex(true, 77), ex(true, 19), ex(true, 25));

        int idx = OcrConsensus.pickExtractionIndex(samples);

        // Tries : 19,25,77 -> mediane basse = 25 (indice 2), ni 77 ni 19.
        assertThat(idx).isEqualTo(2);
        assertThat(samples.get(idx).fields()).hasSize(25);
    }

    @Test
    void when_majority_says_not_found_the_lone_extraction_is_not_trusted() {
        List<CartoucheExtraction> samples = List.of(ex(false, 0), ex(false, 0), ex(true, 5));

        int idx = OcrConsensus.pickExtractionIndex(samples);

        assertThat(samples.get(idx).cartoucheFound()).isFalse();
    }

    @Test
    void null_extraction_counts_as_not_found_and_zero_fields() {
        List<CartoucheExtraction> samples = new java.util.ArrayList<>();
        samples.add(ex(true, 4));
        samples.add(null);
        samples.add(ex(true, 6));

        int idx = OcrConsensus.pickExtractionIndex(samples);

        // Majorite found=true {4,6} ; mediane basse = 4.
        assertThat(samples.get(idx)).isNotNull();
        assertThat(samples.get(idx).fields()).hasSize(4);
    }

    @Test
    void location_consensus_keeps_the_most_frequent_corner() {
        // 12.pdf : la localisation flotte ; deux voix "top-left" contre une "top-center".
        List<CartoucheLocation> samples = List.of(
                loc(true, "top-center"), loc(true, "top-left"), loc(true, "top-left"));

        int idx = OcrConsensus.pickLocationIndex(samples);

        assertThat(samples.get(idx).corner()).isEqualTo("top-left");
    }

    @Test
    void location_corner_vote_is_case_insensitive_and_trimmed() {
        List<CartoucheLocation> samples = List.of(
                loc(true, "Bottom-Right"), loc(true, " bottom-right "), loc(true, "top-left"));

        int idx = OcrConsensus.pickLocationIndex(samples);

        // Les deux premieres sont le meme coin apres normalisation -> il l'emporte, indice 0 en tete.
        assertThat(samples.get(idx).corner().trim().toLowerCase()).isEqualTo("bottom-right");
    }

    @Test
    void location_majority_not_found_returns_a_not_found_sample() {
        List<CartoucheLocation> samples = List.of(
                loc(false, "unknown"), loc(false, "unknown"), loc(true, "bottom-right"));

        int idx = OcrConsensus.pickLocationIndex(samples);

        assertThat(samples.get(idx).cartoucheFound()).isFalse();
    }
}
