package com.secureleaf.content;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.service.JwtService;

class DocumentUploadIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private InMemoryStorageService storage;

    @Autowired
    private JwtService jwtService;

    private User creator;
    private Product product;
    private String token;

    @BeforeEach
    void setUp() {
        storage.clear();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        creator = new User();
        creator.setEmail("c@example.com");
        creator.setDisplayName("Creator");
        creator.setPasswordHash("hash");
        UserRole buyerRole = new UserRole();
        buyerRole.setUser(creator);
        buyerRole.setRole(com.secureleaf.auth.entity.Role.BUYER);
        creator.getRoles().add(buyerRole);

        UserRole creatorRole = new UserRole();
        creatorRole.setUser(creator);
        creatorRole.setRole(com.secureleaf.auth.entity.Role.CREATOR);
        creator.getRoles().add(creatorRole);
        creator = userRepository.save(creator);

        Category cat = new Category();
        cat.setName("Cat");
        cat.setSlug("cat");
        cat = categoryRepository.save(cat);

        product = new Product();
        product.setCreator(creator);
        product.setCategory(cat);
        product.setTitle("Book");
        product.setSlug("book");
        product.setDescription("Desc");
        product = productRepository.save(product);

        token = jwtService.generateAccessToken(creator);
    }

    @Test
    void uploadDocument_success() throws Exception {
        // PDF magic bytes %PDF-
        byte[] pdfContent = new byte[] { '%', 'P', 'D', 'F', '-', '1', '.', '4', '\n' };
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfContent
        );

        mockMvc.perform(multipart("/api/products/{id}/document", product.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void uploadDocument_rejectsNonPdfMagicBytes() throws Exception {
        // Even if mime type is PDF, actual bytes are an image
        byte[] pngContent = new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", MediaType.APPLICATION_PDF_VALUE, pngContent
        );

        mockMvc.perform(multipart("/api/products/{id}/document", product.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File is not a valid PDF. Only PDF files are accepted."));
    }
}
