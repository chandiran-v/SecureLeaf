package com.secureleaf.creator.resolver;

import com.secureleaf.creator.entity.JobStage;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.dto.ProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code Product.processingStage} / {@code Product.failureReason} (Phase 6, D4) — the creator
 * dashboard's "what's actually happening" detail for PROCESSING and FAILED products.
 *
 * Same @BatchMapping shape as {@link com.secureleaf.commerce.resolver.ProductOwnershipResolver}:
 * one query for the whole page of products, keyed by the highest-id (= most recent) job per
 * product — a re-upload always inserts a fresh job row, so "most recent" is always "the attempt
 * that matters right now" (see {@link ProcessingJobRepository#findByProductIdInOrderByIdDesc}).
 *
 * Unlike {@code salesCount}/{@code netEarningsPaise}, these two fields don't need to share a
 * DataLoader across each other: each is already a single aggregate query on its own, and neither
 * needs an ownership check (a product's processing status isn't sensitive the way its revenue
 * is — the public {@code product}/{@code products} queries never return non-LIVE products in the
 * first place, so only the owner's own dashboard query ever sees a non-null value here anyway).
 */
@Controller
@RequiredArgsConstructor
public class ProductProcessingStatusResolver {

    private final ProcessingJobRepository processingJobRepository;

    @BatchMapping(typeName = "Product", field = "processingStage")
    public List<JobStage> processingStage(List<ProductDto> products) {
        Map<Long, ProcessingJob> latestJobByProduct = latestJobByProduct(products);
        return products.stream()
                .map(p -> "PROCESSING".equals(p.status()) ? stageOf(latestJobByProduct.get(p.id())) : null)
                .toList();
    }

    @BatchMapping(typeName = "Product", field = "failureReason")
    public List<String> failureReason(List<ProductDto> products) {
        Map<Long, ProcessingJob> latestJobByProduct = latestJobByProduct(products);
        return products.stream()
                .map(p -> "FAILED".equals(p.status()) ? reasonOf(latestJobByProduct.get(p.id())) : null)
                .toList();
    }

    private static JobStage stageOf(ProcessingJob job) {
        return job == null ? null : job.getCurrentStage();
    }

    private static String reasonOf(ProcessingJob job) {
        return job == null ? null : job.getFailureReason();
    }

    private Map<Long, ProcessingJob> latestJobByProduct(List<ProductDto> products) {
        List<Long> productIds = products.stream().map(ProductDto::id).toList();
        if (productIds.isEmpty()) return Map.of();
        return processingJobRepository.findByProductIdInOrderByIdDesc(productIds).stream()
                .collect(Collectors.toMap(
                        j -> j.getProduct().getId(),
                        j -> j,
                        // Jobs arrive highest-id-first per the repository's ORDER BY; keep the first
                        // (most recent) one seen for each product id.
                        (first, second) -> first,
                        java.util.LinkedHashMap::new));
    }
}
