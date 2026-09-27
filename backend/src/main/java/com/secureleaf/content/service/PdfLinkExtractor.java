package com.secureleaf.content.service;

import com.secureleaf.content.entity.PageLinkType;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a PDF page's link annotations and turns each into a rectangle on the RENDERED page
 * image: fractions 0..1 of its width/height, origin top-left, page rotation applied. That is
 * the same frame {@code PDFRenderer} draws in (crop box, rotated), so the reader can overlay
 * invisible click targets that line up with the watermarked tile at any size.
 *
 * <p>Security: the reader turns URL links into real {@code <a href>}s, so only
 * {@code http}, {@code https} and {@code mailto} survive. A malicious PDF can carry
 * {@code javascript:}, {@code file:} or {@code data:} links, and those are dropped here (and
 * re-checked in the browser).
 */
@Component
@Slf4j
public class PdfLinkExtractor {

    static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");
    static final int MAX_URL_LENGTH = 2048;
    /** A page with thousands of links is a hostile or broken file; cap what we store. */
    static final int MAX_LINKS_PER_PAGE = 200;

    /** A link rectangle as fractions of the rendered page, plus where it points. */
    public record ExtractedLink(double left, double top, double width, double height,
                                PageLinkType type, String url, Integer targetPage) {}

    /**
     * @param pageIndex 0-based page index
     * @return the page's usable links; never throws for a malformed annotation (it's skipped)
     */
    public List<ExtractedLink> extract(PDDocument pdf, int pageIndex) {
        PDPage page = pdf.getPage(pageIndex);
        List<PDAnnotation> annotations;
        try {
            annotations = page.getAnnotations();
        } catch (IOException e) {
            log.warn("Could not read annotations of page {}: {}", pageIndex + 1, e.getMessage());
            return List.of();
        }

        List<ExtractedLink> links = new ArrayList<>();
        for (PDAnnotation annotation : annotations) {
            if (links.size() >= MAX_LINKS_PER_PAGE) break;
            if (!(annotation instanceof PDAnnotationLink link) || link.getRectangle() == null) continue;
            try {
                Optional<double[]> box = normalise(link.getRectangle(), page.getCropBox(), page.getRotation());
                if (box.isEmpty()) continue;
                double[] b = box.get();
                target(pdf, link).ifPresent(t -> links.add(
                        new ExtractedLink(b[0], b[1], b[2], b[3], t.type(), t.url(), t.page())));
            } catch (IOException | RuntimeException e) {
                // One broken annotation must not cost the page (or the upload) its other links.
                log.debug("Skipping malformed link on page {}: {}", pageIndex + 1, e.getMessage());
            }
        }
        return links;
    }

    private record Target(PageLinkType type, String url, Integer page) {}

    private Optional<Target> target(PDDocument pdf, PDAnnotationLink link) throws IOException {
        PDAction action = link.getAction();
        if (action instanceof PDActionURI uriAction) {
            return safeUrl(uriAction.getURI()).map(url -> new Target(PageLinkType.URL, url, null));
        }
        PDDestination destination = action instanceof PDActionGoTo goTo ? goTo.getDestination() : link.getDestination();
        if (destination instanceof PDNamedDestination named) {
            destination = pdf.getDocumentCatalog().findNamedDestinationPage(named);
        }
        if (destination instanceof PDPageDestination pageDestination) {
            int index = pageDestination.retrievePageNumber(); // 0-based, -1 when unknown
            if (index >= 0 && index < pdf.getNumberOfPages()) {
                return Optional.of(new Target(PageLinkType.PAGE, null, index + 1));
            }
        }
        return Optional.empty(); // launch actions, remote files, JavaScript actions, ...: ignored
    }

    /** Only well-formed absolute http(s)/mailto URLs of a sane length. */
    static Optional<String> safeUrl(String raw) {
        if (raw == null) return Optional.empty();
        String url = raw.strip();
        if (url.isEmpty() || url.length() > MAX_URL_LENGTH) return Optional.empty();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) return Optional.empty();
            if (!"mailto".equalsIgnoreCase(scheme) && (uri.getHost() == null || uri.getHost().isBlank())) {
                return Optional.empty();
            }
            return Optional.of(url);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * PDF user space (points, origin bottom-left) → fractions of the rendered image (origin
     * top-left), clipped to the crop box, then rotated like PDFRenderer rotates the page
     * (/Rotate is clockwise). Returns {left, top, width, height}, or empty if nothing is left.
     */
    static Optional<double[]> normalise(PDRectangle rect, PDRectangle crop, int rotation) {
        double cropWidth = crop.getWidth();
        double cropHeight = crop.getHeight();
        if (cropWidth <= 0 || cropHeight <= 0) return Optional.empty();

        double x0 = clamp((rect.getLowerLeftX() - crop.getLowerLeftX()) / cropWidth);
        double x1 = clamp((rect.getUpperRightX() - crop.getLowerLeftX()) / cropWidth);
        double y0 = clamp((crop.getUpperRightY() - rect.getUpperRightY()) / cropHeight); // top
        double y1 = clamp((crop.getUpperRightY() - rect.getLowerLeftY()) / cropHeight);  // bottom
        double left = Math.min(x0, x1), right = Math.max(x0, x1);
        double top = Math.min(y0, y1), bottom = Math.max(y0, y1);

        double[] r = switch (((rotation % 360) + 360) % 360) {
            case 90 -> new double[] {1 - bottom, left, 1 - top, right};
            case 180 -> new double[] {1 - right, 1 - bottom, 1 - left, 1 - top};
            case 270 -> new double[] {top, 1 - right, bottom, 1 - left};
            default -> new double[] {left, top, right, bottom};
        };
        double width = r[2] - r[0];
        double height = r[3] - r[1];
        if (width <= 0.0005 || height <= 0.0005) return Optional.empty(); // degenerate / fully clipped
        return Optional.of(new double[] {r[0], r[1], width, height});
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
