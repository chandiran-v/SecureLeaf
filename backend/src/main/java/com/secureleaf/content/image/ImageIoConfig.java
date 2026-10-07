package com.secureleaf.content.image;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import javax.imageio.ImageIO;

/**
 * Turns off ImageIO's disk cache for the whole JVM, once, at startup.
 *
 * <p>By default {@code ImageIO.read(InputStream)} and {@code ImageIO.createImageOutputStream(...)}
 * spool every image through a temporary FILE. Our images are already fully in memory (byte arrays
 * from storage, BufferedImages from PDFBox), so that file is pure overhead: measured, it was ~20%
 * of the CPU of decoding a tile, watermarking it and encoding it again. With the cache off, ImageIO
 * uses in-memory streams instead.
 *
 * <p>It is a JVM-wide static switch, so it lives in one visible place rather than as a hidden side
 * effect of whichever image class happens to load first.
 */
@Configuration
@Slf4j
public class ImageIoConfig {

    @PostConstruct
    void disableImageIoDiskCache() {
        ImageIO.setUseCache(false);
        log.info("ImageIO disk cache disabled (images are encoded and decoded in memory)");
    }
}
