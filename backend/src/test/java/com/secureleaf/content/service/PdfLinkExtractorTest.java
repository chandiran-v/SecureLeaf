package com.secureleaf.content.service;

import com.secureleaf.content.entity.PageLinkType;
import com.secureleaf.content.service.PdfLinkExtractor.ExtractedLink;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** V8 — turning a PDF page's link annotations into rectangles on the rendered tile. */
class PdfLinkExtractorTest {

    private final PdfLinkExtractor extractor = new PdfLinkExtractor();

    private static PDAnnotationLink uriLink(PDRectangle rect, String uri) {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(rect);
        PDActionURI action = new PDActionURI();
        action.setURI(uri);
        link.setAction(action);
        return link;
    }

    private static PDAnnotationLink pageLink(PDRectangle rect, PDPage target) {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(rect);
        PDPageFitDestination destination = new PDPageFitDestination();
        destination.setPage(target);
        PDActionGoTo goTo = new PDActionGoTo();
        goTo.setDestination(destination);
        link.setAction(goTo);
        return link;
    }

    private static void annotate(PDPage page, PDAnnotation... annotations) {
        page.setAnnotations(new ArrayList<>(List.of(annotations)));
    }

    @Test
    void urlLink_becomesFractionsOfThePage_withATopLeftOrigin() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 800));
            pdf.addPage(page);
            // 200×20 pt box whose bottom-left corner is at (100, 700) in PDF space (origin bottom-left).
            annotate(page, uriLink(new PDRectangle(100, 700, 200, 20), "https://example.com/docs"));

            List<ExtractedLink> links = extractor.extract(pdf, 0);

            assertThat(links).hasSize(1);
            ExtractedLink link = links.get(0);
            assertThat(link.type()).isEqualTo(PageLinkType.URL);
            assertThat(link.url()).isEqualTo("https://example.com/docs");
            assertThat(link.left()).isCloseTo(100.0 / 600, within(1e-6));
            assertThat(link.top()).isCloseTo((800.0 - 720) / 800, within(1e-6)); // 80 pt from the TOP
            assertThat(link.width()).isCloseTo(200.0 / 600, within(1e-6));
            assertThat(link.height()).isCloseTo(20.0 / 800, within(1e-6));
        }
    }

    @Test
    void internalLink_pointsAtTheTargetPageNumber() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage first = new PDPage();
            PDPage third = new PDPage();
            pdf.addPage(first);
            pdf.addPage(new PDPage());
            pdf.addPage(third);
            annotate(first, pageLink(new PDRectangle(10, 10, 50, 10), third));

            List<ExtractedLink> links = extractor.extract(pdf, 0);

            assertThat(links).singleElement().satisfies(link -> {
                assertThat(link.type()).isEqualTo(PageLinkType.PAGE);
                assertThat(link.targetPage()).isEqualTo(3);
                assertThat(link.url()).isNull();
            });
        }
    }

    @Test
    void dangerousOrBrokenUrls_areDropped_safeOnesKept() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            PDRectangle r = new PDRectangle(10, 10, 50, 10);
            annotate(page,
                    uriLink(r, "javascript:alert(document.cookie)"),
                    uriLink(r, "JavaScript:alert(1)"),
                    uriLink(r, "file:///etc/passwd"),
                    uriLink(r, "data:text/html,<script>alert(1)</script>"),
                    uriLink(r, "https://"),                      // no host
                    uriLink(r, "not a url at all"),
                    uriLink(r, "HTTPS://Example.com/ok"),        // scheme is case-insensitive
                    uriLink(r, "mailto:author@example.com"));

            List<String> urls = extractor.extract(pdf, 0).stream().map(ExtractedLink::url).toList();

            assertThat(urls).containsExactly("HTTPS://Example.com/ok", "mailto:author@example.com");
        }
    }

    @Test
    void rotatedPage_rotatesTheRectangleClockwise_likeTheRenderer() {
        // 100×200 page, 10×10 box in its TOP-LEFT corner.
        PDRectangle crop = new PDRectangle(0, 0, 100, 200);
        PDRectangle topLeftCorner = new PDRectangle(0, 190, 10, 10);

        double[] unrotated = PdfLinkExtractor.normalise(topLeftCorner, crop, 0).orElseThrow();
        assertThat(unrotated).containsExactly(new double[] {0, 0, 0.1, 0.05}, within(1e-9));

        // Rotated 90° clockwise, the top-left corner ends up TOP-RIGHT, and width/height swap.
        double[] rotated90 = PdfLinkExtractor.normalise(topLeftCorner, crop, 90).orElseThrow();
        assertThat(rotated90).containsExactly(new double[] {0.95, 0, 0.05, 0.1}, within(1e-9));

        // 180°: bottom-right.
        double[] rotated180 = PdfLinkExtractor.normalise(topLeftCorner, crop, 180).orElseThrow();
        assertThat(rotated180).containsExactly(new double[] {0.9, 0.95, 0.1, 0.05}, within(1e-9));

        // 270°: bottom-left.
        double[] rotated270 = PdfLinkExtractor.normalise(topLeftCorner, crop, 270).orElseThrow();
        assertThat(rotated270).containsExactly(new double[] {0, 0.9, 0.05, 0.1}, within(1e-9));
    }

    @Test
    void cropBoxOffset_isSubtracted_andLinksOutsideItAreDropped() {
        PDRectangle crop = new PDRectangle(50, 50, 100, 100); // visible area starts at (50, 50)

        double[] inside = PdfLinkExtractor.normalise(new PDRectangle(50, 140, 50, 10), crop, 0).orElseThrow();
        assertThat(inside).containsExactly(new double[] {0, 0, 0.5, 0.1}, within(1e-9));

        assertThat(PdfLinkExtractor.normalise(new PDRectangle(0, 0, 20, 20), crop, 0)).isEmpty();
    }

    @Test
    void aPageWithoutLinks_hasNone() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            assertThat(extractor.extract(pdf, 0)).isEmpty();
        }
    }
}
