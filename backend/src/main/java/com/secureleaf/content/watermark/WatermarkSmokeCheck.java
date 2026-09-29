package com.secureleaf.content.watermark;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Phase 09D, D6 — a tiny standalone check that the watermark renderer works in THIS runtime image.
 *
 * Why it exists: Java2D draws watermark text with system fonts. A slim container image often has
 * none, which fails ("Fontconfig head is null") or silently draws nothing — and it does so only in
 * production, never on a developer laptop. This renders a label onto a blank white PNG and exits
 * non-zero unless pixels actually changed.
 *
 * Run inside the built image (see infra/prod/scripts/smoke-watermark.sh):
 * {@code java -Dloader.main=com.secureleaf.content.watermark.WatermarkSmokeCheck
 * -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher}
 */
public final class WatermarkSmokeCheck {

    private WatermarkSmokeCheck() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        BufferedImage blank = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 400);
        g.dispose();
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        ImageIO.write(blank, "png", in);

        byte[] marked = new Java2DWatermarkRenderer(0.25f, 24)
                .applyWatermark(in.toByteArray(), "SMOKE CHECK buyer@example.com");

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(marked));
        int changed = 0;
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                if ((result.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    changed++;
                }
            }
        }
        if (changed == 0) {
            System.err.println("WATERMARK SMOKE CHECK FAILED: no pixels changed (fonts missing?)");
            System.exit(1);
        }
        System.out.println("WATERMARK SMOKE CHECK OK: " + changed + " pixels changed");
    }
}
