package com.secureleaf.content;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PreviewControllerIT — the DRM boundary test.
 *
 * "Anything out of the free-preview page range is a 404, not a 403" (D5). The
 * page-beyond-freePreviewPages case is the single most important assertion in
 * this whole phase: it is the one place where getting it wrong leaks a clean
 * tile.
 *
 * No Authorization header is sent anywhere in this class — the preview endpoint
 * is public.
 */
class PreviewControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private DocumentVersionRepository documentVersionRepository;
    @Autowired
    private ContentPageRepository contentPageRepository;
    @Autowired
    private InMemoryStorageService storage;

    private static final byte[] CLEAN_PAGE_PNG = fakePng();

    private Product liveProduct;

    @BeforeEach
    void setUp() {
        storage.clear();
        contentPageRepository.deleteAll();
        documentVersionRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        User creator = new User();
        creator.setEmail("creator2@example.com");
        creator.setDisplayName("Creator Two");
        creator.setPasswordHash("hash");
        creator = userRepository.save(creator);

        Category category = new Category();
        category.setName("Nonfiction");
        category.setSlug("nonfiction");
        category = categoryRepository.save(category);

        liveProduct = new Product();
        liveProduct.setCreator(creator);
        liveProduct.setCategory(category);
        liveProduct.setTitle("Preview Test Book");
        liveProduct.setSlug("preview-test-book-" + System.nanoTime());
        liveProduct.setDescription("desc");
        liveProduct.setStatus(ProductStatus.LIVE);
        liveProduct.setFreePreviewPages(2);
        liveProduct = productRepository.save(liveProduct);

        DocumentVersion version = new DocumentVersion();
        version.setProduct(liveProduct);
        version.setVersionNumber(1);
        version.setOriginalFilename("book.pdf");
        version.setFileSizeBytes(1234L);
        version.setRawMinioBucket("raw");
        version.setRawMinioObjectKey("raw/key.pdf");
        version.setPageCount(5);
        version = documentVersionRepository.save(version);

        for (int page = 1; page <= 5; page++) {
            String key = "products/%d/v1/page-%d.png".formatted(liveProduct.getId(), page);
            storage.put("tiles", key, CLEAN_PAGE_PNG, "image/png");

            ContentPage cp = new ContentPage();
            cp.setDocumentVersion(version);
            cp.setPageNumber(page);
            cp.setBucketName("tiles");
            cp.setMinioObjectKey(key);
            contentPageRepository.save(cp);
        }
    }

    @Test
    void pageWithinFreePreviewRange_returns200Png() throws Exception {
        mockMvc.perform(get("/api/products/{id}/preview/{page}", liveProduct.getId(), 1))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void pageBeyondFreePreviewPages_returns404_theCoreDrmBoundaryTest() throws Exception {
        // freePreviewPages = 2, so page 3 (which exists in the document) must still 404.
        mockMvc.perform(get("/api/products/{id}/preview/{page}", liveProduct.getId(), 3))
                .andExpect(status().isNotFound());
    }

    @Test
    void pageZeroOrNegative_returns404() throws Exception {
        mockMvc.perform(get("/api/products/{id}/preview/{page}", liveProduct.getId(), 0))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewOfNonLiveProduct_returns404() throws Exception {
        liveProduct.setStatus(ProductStatus.DRAFT);
        productRepository.save(liveProduct);

        mockMvc.perform(get("/api/products/{id}/preview/{page}", liveProduct.getId(), 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewOfSoftDeletedProduct_returns404() throws Exception {
        liveProduct.setDeletedAt(Instant.now());
        productRepository.save(liveProduct);

        mockMvc.perform(get("/api/products/{id}/preview/{page}", liveProduct.getId(), 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewBytes_differFromStoredCleanTile_provingWatermarkWasApplied() throws Exception {
        byte[] returned = mockMvc.perform(get("/api/products/{id}/preview/{page}", liveProduct.getId(), 1))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(returned).isNotEqualTo(CLEAN_PAGE_PNG);
    }

    /**
     * A minimal but valid PNG, so ImageIO can decode it for watermarking. 200x200, not 10x10:
     * Java2DWatermarkRenderer draws its repeating label on a grid sized off the image dimensions
     * (see its {@code stepX}/{@code stepY}), so on a canvas much smaller than one grid cell the
     * single diagonal draw call can land entirely outside the visible pixels — the watermark
     * silently no-ops and {@code returned}/{@code CLEAN_PAGE_PNG} come out byte-identical
     * (observed as a flaky failure of this exact test). Large enough that at least one repeat
     * always overlaps the canvas, regardless of font metrics.
     */
    private static byte[] fakePng() {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    200, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
