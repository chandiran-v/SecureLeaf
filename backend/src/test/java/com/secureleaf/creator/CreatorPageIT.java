package com.secureleaf.creator;

import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.commerce.AbstractCommerceIT;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.repository.ContentPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner preview — the creator sees their own pages clean; nobody else gets them at all.
 * The "rival creator gets 404" case is the one that matters: it is the only thing standing
 * between another creator account and a clean copy of someone else's book.
 */
class CreatorPageIT extends AbstractCommerceIT {

    private static final String PATH = "/api/creator/products/{id}/pages/{page}";
    private static final byte[] CLEAN_PAGE = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @Autowired private ContentPageRepository contentPageRepository;
    @Autowired private InMemoryStorageService storage;

    @BeforeEach
    void storePages() {
        storage.clear();
        // paidProduct (AbstractCommerceIT) is LIVE, owned by `creator`, with a 10-page current version.
        for (int page = 1; page <= 10; page++) {
            String key = "products/%d/v1/page-%d.png".formatted(paidProduct.getId(), page);
            storage.put("tiles", key, CLEAN_PAGE, "image/png");
            ContentPage cp = new ContentPage();
            cp.setDocumentVersion(paidProduct.getCurrentDocumentVersion());
            cp.setPageNumber(page);
            cp.setBucketName("tiles");
            cp.setMinioObjectKey(key);
            contentPageRepository.save(cp);
        }
    }

    private ResultActions fetch(User as, long productId, int page) throws Exception {
        return mockMvc.perform(get(PATH, productId, page)
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(as)));
    }

    @Test
    void owner_getsTheCleanStoredBytes_notWatermarked() throws Exception {
        byte[] body = fetch(creator, paidProduct.getId(), 1)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(CLEAN_PAGE);
    }

    /** Regression: myProducts used to return pageCount = null, so the dashboard never offered the preview. */
    @Test
    void myProducts_returnsTheCurrentVersionsPageCount_soTheDashboardCanLinkToThePreview() {
        asCreator.document("query { myProducts { id pageCount } }").execute()
                .path("myProducts[?(@.id == '%d')].pageCount".formatted(paidProduct.getId()))
                .entityList(Integer.class).containsExactly(10);
    }

    @Test
    void rivalCreator_gets404_neverTheCleanPage() throws Exception {
        User rival = makeUser("rival@example.com", "Rita Rival", Role.BUYER, Role.CREATOR);
        fetch(rival, paidProduct.getId(), 1).andExpect(status().isNotFound());
    }

    @Test
    void buyer_evenOneWhoOwnsIt_isForbiddenAtTheHttpLayer() throws Exception {
        fetch(buyer, paidProduct.getId(), 1).andExpect(status().isForbidden());
    }

    @Test
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get(PATH, paidProduct.getId(), 1)).andExpect(status().isUnauthorized());
    }

    @Test
    void pageOutOfRange_returns404() throws Exception {
        fetch(creator, paidProduct.getId(), 0).andExpect(status().isNotFound());
        fetch(creator, paidProduct.getId(), 11).andExpect(status().isNotFound());
    }

    @Test
    void softDeletedProduct_returns404() throws Exception {
        paidProduct.setDeletedAt(Instant.now());
        productRepository.save(paidProduct);
        fetch(creator, paidProduct.getId(), 1).andExpect(status().isNotFound());
    }
}
