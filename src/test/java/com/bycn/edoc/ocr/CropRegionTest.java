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

    @Test
    void from_box_builds_the_rectangle_and_clamps_out_of_range_edges() {
        CropRegion r = CropRegion.fromBox(0.60, 0.70, 1.20, 0.95, 0.02);
        assertThat(r.x()).isCloseTo(0.60, within(1e-9));
        assertThat(r.y()).isCloseTo(0.70, within(1e-9));
        assertThat(r.w()).isCloseTo(0.40, within(1e-9)); // droite serrée à 1.0
        assertThat(r.h()).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void from_box_rejects_degenerate_boxes() {
        // Bords inversés, ou boîte de quelques pourcents : localisation ratée, pas un découpage.
        assertThat(CropRegion.fromBox(0.8, 0.8, 0.4, 0.9, 0.02)).isNull();
        assertThat(CropRegion.fromBox(0.50, 0.50, 0.505, 0.90, 0.02)).isNull();
    }

    @Test
    void expanded_adds_a_margin_on_each_side_and_stays_inside_the_page() {
        CropRegion r = new CropRegion(0.90, 0.10, 0.08, 0.20).expanded(0.05);
        assertThat(r.x()).isCloseTo(0.85, within(1e-9));
        assertThat(r.y()).isCloseTo(0.05, within(1e-9));
        assertThat(r.x() + r.w()).isCloseTo(1.0, within(1e-9)); // serré au bord droit
        assertThat(r.h()).isCloseTo(0.30, within(1e-9));
    }

    @Test
    void zone_label_names_the_grid_cell_of_the_rectangle_center() {
        assertThat(new CropRegion(0.75, 0.75, 0.2, 0.2).zoneLabel()).isEqualTo("bottom-right");
        assertThat(new CropRegion(0.0, 0.0, 0.2, 0.2).zoneLabel()).isEqualTo("top-left");
        assertThat(new CropRegion(0.9, 0.4, 0.1, 0.2).zoneLabel()).isEqualTo("right");
        assertThat(new CropRegion(0.4, 0.45, 0.2, 0.1).zoneLabel()).isEqualTo("center");
    }
}
