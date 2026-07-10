package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CropRegionTest {

    private static final double F = 0.40;
    private static final double B = 0.70;

    @Test
    void bottom_right_takes_a_corner_square_anchored_bottom_right() {
        CropRegion r = CropRegion.forCorner("bottom-right", F, B);
        assertThat(r.x()).isCloseTo(0.60, within(1e-9));
        assertThat(r.y()).isCloseTo(0.60, within(1e-9));
        assertThat(r.w()).isCloseTo(F, within(1e-9));
        assertThat(r.h()).isCloseTo(F, within(1e-9));
        assertThat(r.isFullPage()).isFalse();
    }

    @Test
    void top_left_is_anchored_at_origin() {
        CropRegion r = CropRegion.forCorner("top-left", F, B);
        assertThat(r.x()).isCloseTo(0.0, within(1e-9));
        assertThat(r.y()).isCloseTo(0.0, within(1e-9));
        assertThat(r.w()).isCloseTo(F, within(1e-9));
        assertThat(r.h()).isCloseTo(F, within(1e-9));
    }

    @Test
    void bottom_center_uses_a_wide_band_centered_horizontally() {
        CropRegion r = CropRegion.forCorner("bottom-center", F, B);
        assertThat(r.w()).isCloseTo(B, within(1e-9));
        assertThat(r.h()).isCloseTo(F, within(1e-9));
        assertThat(r.x()).isCloseTo((1 - B) / 2, within(1e-9));
        assertThat(r.y()).isCloseTo(1 - F, within(1e-9));
        // reste dans la page
        assertThat(r.x() + r.w()).isLessThanOrEqualTo(1.0 + 1e-9);
        assertThat(r.y() + r.h()).isLessThanOrEqualTo(1.0 + 1e-9);
    }

    @Test
    void unknown_zone_falls_back_to_full_page() {
        assertThat(CropRegion.forCorner("unknown", F, B).isFullPage()).isTrue();
        assertThat(CropRegion.forCorner(null, F, B).isFullPage()).isTrue();
    }
}
