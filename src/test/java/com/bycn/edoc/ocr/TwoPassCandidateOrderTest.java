package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TwoPassCandidateOrderTest {

    @Test
    void pass1_zone_is_tried_first_then_the_three_other_corners() {
        // Zone passe 1 = un coin : on essaie ce coin, puis les 3 autres (4 essais max).
        assertThat(TwoPassCartoucheExtractor.candidateOrder("bottom-right"))
                .containsExactly("bottom-right", "bottom-left", "top-right", "top-left");
    }

    @Test
    void a_non_corner_zone_is_tried_first_then_all_four_corners() {
        // Zone passe 1 = un bord/centre : on l'essaie, puis les 4 coins dans l'ordre.
        assertThat(TwoPassCartoucheExtractor.candidateOrder("top-center"))
                .containsExactly("top-center", "bottom-right", "bottom-left", "top-right", "top-left");
    }
}
