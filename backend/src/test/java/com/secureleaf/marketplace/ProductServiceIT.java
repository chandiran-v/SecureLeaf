package com.secureleaf.marketplace;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.marketplace.dto.CreateProductInput;
import com.secureleaf.marketplace.dto.ProductDto;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.marketplace.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    private User creatorA;
    private User creatorB;
    private Category category;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        creatorA = new User();
        creatorA.setEmail("a@example.com");
        creatorA.setDisplayName("Creator A");
        creatorA.setPasswordHash("hash");
        creatorA = userRepository.save(creatorA);

        creatorB = new User();
        creatorB.setEmail("b@example.com");
        creatorB.setDisplayName("Creator B");
        creatorB.setPasswordHash("hash");
        creatorB = userRepository.save(creatorB);

        category = new Category();
        category.setName("Tech");
        category.setSlug("tech");
        category = categoryRepository.save(category);
    }

    @Test
    void createProduct_success() {
        CreateProductInput input = new CreateProductInput(
                "My Book", "Desc", 1000, category.getId(), List.of("tag1", "TAG2"), 3
        );

        ProductDto dto = productService.createProduct(input, creatorA.getId());

        assertThat(dto.title()).isEqualTo("My Book");
        assertThat(dto.pricePaise()).isEqualTo(1000);
        assertThat(dto.status()).isEqualTo("DRAFT");
        assertThat(dto.tags()).containsExactly("tag1", "tag2"); // normalized to lowercase

        Product entity = productRepository.findById(dto.id()).orElseThrow();
        assertThat(entity.getSlug()).isEqualTo("my-book"); // generated slug
    }

    @Test
    void assertOwnership_preventsBola() {
        // Creator A creates a product
        CreateProductInput input = new CreateProductInput(
                "A's Book", "Desc", 1000, category.getId(), List.of(), 0
        );
        ProductDto dto = productService.createProduct(input, creatorA.getId());

        // Creator B tries to unpublish it
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            productService.unpublishProduct(dto.id(), creatorB.getId());
        });

        assertThat(ex.getMessage()).contains("do not have permission");

        // Creator B tries to delete it
        BusinessException ex2 = assertThrows(BusinessException.class, () -> {
            productService.deleteProduct(dto.id(), creatorB.getId());
        });

        assertThat(ex2.getMessage()).contains("do not have permission");
    }
}
