package com.secureleaf.content.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * The one place page images are encoded as PNG: page tiles (processing), thumbnails and watermarked
 * pages all go through here.
 *
 * <p>Measured on a real 1239x1754 page tile (CPU time per watermarked page, one thread):
 * <pre>
 *   ImageIO.write(png), file cache on, INT_RGB   263 ms   338 KB   (before)
 *   zlib level 2,    file cache off, 3BYTE_BGR   157 ms   349 KB   (this class + ImageIoConfig)
 *   zlib level 1,    file cache off, 3BYTE_BGR   155 ms   358 KB   (same CPU within noise, bigger)
 *   JPEG q85,        file cache off, 3BYTE_BGR   134 ms   298 KB   (rejected: lossy on text)
 * </pre>
 * Encoding, not drawing the watermark text (4-8 ms), is where the time goes. Level 2 instead of
 * the default 4 gives up ~3% of size for ~30% less encode CPU. PNG stays lossless, so text edges
 * stay crisp and every consumer keeps seeing {@code image/png}.
 */
public final class PngEncoder {

    /**
     * JDK 21's PNG writer turns an explicit quality q into zlib level {@code 9 - round(9 * q)}
     * (PNGImageWriter; the default with no explicit quality is level 4). 0.8 gives level 2. 1.0 would
     * be level 0, no compression at all, and files several times larger.
     */
    static final float FAST_COMPRESSION_QUALITY = 0.8f;

    private PngEncoder() {
    }

    public static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(FAST_COMPRESSION_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
