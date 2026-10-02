package com.secureleaf.content.tiles;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Phase 16, D3 — renders one page at one variant straight from the PDF's vector content. Never
 * downscales another raster: text and lines are re-drawn at the target size, which is sharper than
 * resampling (and the same reason a 900 px render looks better than a shrunk 1240 px one).
 */
@Component
public class PageTileRenderer {

    public BufferedImage render(PDDocument pdf, PDFRenderer renderer, int pageIndex,
                                TileVariantProperties.Variant variant) throws IOException {
        if (variant.dpi() != null) {
            return renderer.renderImageWithDPI(pageIndex, variant.dpi());
        }
        // PDFBox sizes the image floor(pageWidthPts * scale). The tiny epsilon keeps floating-point
        // error from producing 899 when 900 was asked for.
        float scale = (variant.widthPx() + 0.01f) / visibleWidthPoints(pdf.getPage(pageIndex));
        return renderer.renderImage(pageIndex, scale);
    }

    /** Page width as displayed: the crop box, swapped for pages rotated a quarter turn. */
    private static float visibleWidthPoints(PDPage page) {
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        return rotation == 90 || rotation == 270 ? box.getHeight() : box.getWidth();
    }
}
