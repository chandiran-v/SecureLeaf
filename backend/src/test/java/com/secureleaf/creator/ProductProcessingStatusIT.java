package com.secureleaf.creator;

import com.secureleaf.commerce.AbstractCommerceIT;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 6, D4 — {@code Product.processingStage} / {@code Product.failureReason} through real
 * GraphQL. These two fields shipped with no test that ever selected them, and every query that
 * did (the creator dashboard's {@code myProducts}, and the public {@code products}) failed with
 * INTERNAL_SERVER_ERROR on each product. Found in manual testing — see the Phase 06 note's
 * "Gotchas" table.
 */
class ProductProcessingStatusIT extends AbstractCommerceIT {

    private static final String MY_PRODUCTS_STATUS = """
            query { myProducts { id title status processingStage failureReason } }
            """;

    private static final String PUBLIC_PRODUCTS_STATUS = """
            query { products(page: 0, size: 10) { content { id processingStage failureReason } } }
            """;

    private void insertJob(long productId, String status, String stage, String failureReason) {
        long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM document_versions WHERE product_id = ?", Long.class, productId);
        jdbcTemplate.update("""
                INSERT INTO processing_jobs (document_version_id, product_id, status, current_stage, failure_reason)
                VALUES (?, ?, CAST(? AS job_status), CAST(? AS job_stage), ?)
                """, versionId, productId, status, stage, failureReason);
    }

    private void setProductStatus(long productId, String status) {
        jdbcTemplate.update("UPDATE products SET status = CAST(? AS product_status) WHERE id = ?", status, productId);
    }

    @Test
    void liveProductsWithCompletedJobs_resolveBothFieldsToNull_withoutErrors() {
        // Exactly the state of every product that went through the pipeline successfully.
        insertJob(paidProduct.getId(), "COMPLETED", "MARK_LIVE", null);
        insertJob(freeProduct.getId(), "COMPLETED", "MARK_LIVE", null);

        var response = asCreator.document(MY_PRODUCTS_STATUS).execute();

        response.errors().verify();
        response.path("myProducts[*].processingStage").entityList(Object.class).hasSize(2);
        response.path("myProducts[0].processingStage").valueIsNull();
        response.path("myProducts[0].failureReason").valueIsNull();
        response.path("myProducts[1].processingStage").valueIsNull();
        response.path("myProducts[1].failureReason").valueIsNull();
    }

    @Test
    void processingProduct_showsItsCurrentStage_andFailedProduct_showsItsReason() {
        setProductStatus(paidProduct.getId(), "PROCESSING");
        insertJob(paidProduct.getId(), "PROCESSING", "CONVERT_TILES", null);
        setProductStatus(freeProduct.getId(), "FAILED");
        insertJob(freeProduct.getId(), "FAILED", "VALIDATE", "Not a valid PDF");

        var response = asCreator.document(MY_PRODUCTS_STATUS).execute();

        response.errors().verify();
        List<String> titles = response.path("myProducts[*].title").entityList(String.class).get();
        int processing = titles.indexOf("Paid Guide");
        int failed = titles.indexOf("Free Guide");
        response.path("myProducts[%d].processingStage".formatted(processing)).entity(String.class).isEqualTo("CONVERT_TILES");
        response.path("myProducts[%d].failureReason".formatted(processing)).valueIsNull();
        response.path("myProducts[%d].failureReason".formatted(failed)).entity(String.class).isEqualTo("Not a valid PDF");
        response.path("myProducts[%d].processingStage".formatted(failed)).valueIsNull();
    }

    @Test
    void latestJobWins_whenAProductWasRetried() {
        setProductStatus(paidProduct.getId(), "FAILED");
        insertJob(paidProduct.getId(), "FAILED", "VALIDATE", "first attempt");
        insertJob(paidProduct.getId(), "FAILED", "CONVERT_TILES", "second attempt");

        var response = asCreator.document(MY_PRODUCTS_STATUS).execute();

        response.errors().verify();
        List<String> titles = response.path("myProducts[*].title").entityList(String.class).get();
        response.path("myProducts[%d].failureReason".formatted(titles.indexOf("Paid Guide")))
                .entity(String.class).isEqualTo("second attempt");
    }

    @Test
    void publicProductListing_canSelectTheFields_withoutErrors() {
        insertJob(paidProduct.getId(), "COMPLETED", "MARK_LIVE", null);

        anonymous.document(PUBLIC_PRODUCTS_STATUS).execute().errors().verify();
    }
}
