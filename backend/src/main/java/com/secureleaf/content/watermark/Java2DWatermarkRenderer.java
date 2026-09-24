package com.secureleaf.content.watermark;

import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Java2D implementation of {@link WatermarkRenderer} — renders a translucent, diagonal,
 * repeating text label across the page image using the JDK's own 2D graphics API.
 *
 * Chosen over a native image library (e.g. libvips) for MVP: zero extra runtime
 * dependency, "good enough" quality and speed for page-sized PNGs, and it keeps the
 * Strategy interface's second implementation (a future high-throughput renderer)
 * genuinely optional rather than something we had to build on day one.
 */
@Component
@Slf4j
public class Java2DWatermarkRenderer implements WatermarkRenderer {

    private final float opacity;
    private final int fontSize;

    public Java2DWatermarkRenderer(
            @Value("${drm.watermark.opacity:0.25}") float opacity,
            @Value("${drm.watermark.font-size:24}") int fontSize) {
        this.opacity = opacity;
        this.fontSize = fontSize;
    }

    @Override
    public byte[] applyWatermark(byte[] imageBytes, String label) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null) {
                throw new BusinessException(ErrorCode.INVALID_FILE, "Page image could not be decoded.");
            }

            BufferedImage result = new BufferedImage(
                    source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = result.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, null);

            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            g.setColor(Color.GRAY);
            g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
            g.rotate(-Math.PI / 6, source.getWidth() / 2.0, source.getHeight() / 2.0);

            int stepX = source.getWidth() / 2 + fontSize * 6;
            int stepY = source.getHeight() / 4 + fontSize * 4;
            for (int y = -source.getHeight(); y < source.getHeight() * 2; y += stepY) {
                for (int x = -source.getWidth(); x < source.getWidth() * 2; x += stepX) {
                    g.drawString(label, x, y);
                }
            }
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(result, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "Failed to render watermark.", e);
        }
    }
}
