package com.secureleaf.content.watermark;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16, D6 / acceptance criterion 5 — the watermark has the same visual weight on every
 * variant: its pixel coverage on a 900 px page is in the same band as on a 1240 px page, and it is
 * neither invisible nor a wall of ink. Coverage is measured on a blank white page, so every
 * non-white pixel is watermark.
 */
class Java2DWatermarkRendererTest {

    private static final String LABEL = "buyer@example.com · #42 · 2026-10-01 UTC · s7";

    private final Java2DWatermarkRenderer renderer = new Java2DWatermarkRenderer(0.25f, 24, 1240);

    @Test
    void coverageStaysLegibleAndComparable_atMobileAndDesktopWidths() throws Exception {
        double mobile = coverage(900, 1273);   // A4 proportions
        double desktop = coverage(1240, 1754);

        assertThat(mobile).as("mobile coverage").isBetween(0.007, 0.04);
        assertThat(desktop).as("desktop coverage").isBetween(0.007, 0.04);
        assertThat(mobile / desktop).as("same visual weight on both variants").isBetween(0.6, 1.6);
    }

    @Test
    void fontSizeScalesWithWidth_butNeverBelowTheMinimum() {
        assertThat(Java2DWatermarkRenderer.scaledFontSize(24, 1240, 1240)).isEqualTo(24);
        assertThat(Java2DWatermarkRenderer.scaledFontSize(24, 900, 1240)).isEqualTo(17);
        assertThat(Java2DWatermarkRenderer.scaledFontSize(24, 2480, 1240)).isEqualTo(48);
        assertThat(Java2DWatermarkRenderer.scaledFontSize(24, 200, 1240)).isEqualTo(Java2DWatermarkRenderer.MIN_FONT_SIZE);
    }

    private double coverage(int width, int height) throws Exception {
        BufferedImage blank = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        ImageIO.write(blank, "png", in);

        BufferedImage marked = ImageIO.read(new ByteArrayInputStream(renderer.applyWatermark(in.toByteArray(), LABEL)));
        long inked = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((marked.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) inked++;
            }
        }
        double fraction = (double) inked / ((long) width * height);
        return fraction;
    }
}
