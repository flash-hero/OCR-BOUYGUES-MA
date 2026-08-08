package com.bycn.edoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageOpsTest {

    /** Un PNG 3×2 avec un pixel rouge en haut-gauche : assez pour suivre la géométrie d'une rotation. */
    private static byte[] probePng() throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                image.setRGB(x, y, 0xFFFFFF);
            }
        }
        image.setRGB(0, 0, 0xFF0000);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return bos.toByteArray();
    }

    private static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void quarter_turn_clockwise_swaps_dimensions_and_moves_the_top_left_pixel_to_the_top_right() throws Exception {
        BufferedImage rotated = decode(ImageOps.rotate(probePng(), "90-cw"));

        assertThat(rotated.getWidth()).isEqualTo(2);
        assertThat(rotated.getHeight()).isEqualTo(3);
        assertThat(rotated.getRGB(1, 0) & 0xFFFFFF).isEqualTo(0xFF0000);
    }

    @Test
    void quarter_turn_counter_clockwise_moves_the_top_left_pixel_to_the_bottom_left() throws Exception {
        BufferedImage rotated = decode(ImageOps.rotate(probePng(), "90-ccw"));

        assertThat(rotated.getWidth()).isEqualTo(2);
        assertThat(rotated.getHeight()).isEqualTo(3);
        assertThat(rotated.getRGB(0, 2) & 0xFFFFFF).isEqualTo(0xFF0000);
    }

    @Test
    void half_turn_keeps_dimensions_and_moves_the_top_left_pixel_to_the_bottom_right() throws Exception {
        BufferedImage rotated = decode(ImageOps.rotate(probePng(), "180"));

        assertThat(rotated.getWidth()).isEqualTo(3);
        assertThat(rotated.getHeight()).isEqualTo(2);
        assertThat(rotated.getRGB(2, 1) & 0xFFFFFF).isEqualTo(0xFF0000);
    }

    @Test
    void none_or_unknown_rotation_returns_the_bytes_untouched() throws Exception {
        byte[] png = probePng();
        assertThat(ImageOps.rotate(png, "none")).isSameAs(png);
        assertThat(ImageOps.rotate(png, null)).isSameAs(png);
        assertThat(ImageOps.rotate(png, "diagonale")).isSameAs(png);
    }

    @Test
    void unreadable_bytes_are_returned_untouched_never_an_exception() {
        byte[] notAnImage = {1, 2, 3};
        assertThat(ImageOps.rotate(notAnImage, "90-cw")).isSameAs(notAnImage);
    }
}
