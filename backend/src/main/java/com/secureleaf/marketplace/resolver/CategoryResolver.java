package com.secureleaf.marketplace.resolver;

import com.secureleaf.marketplace.dto.CategoryDto;
import com.secureleaf.marketplace.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/** Public — powers the marketplace's category filter dropdown. */
@Controller
@RequiredArgsConstructor
public class CategoryResolver {

    private final ProductSearchService productSearchService;

    @QueryMapping
    public List<CategoryDto> categories() {
        return productSearchService.listCategories();
    }
}
