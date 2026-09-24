package com.secureleaf.marketplace.repository;

import com.secureleaf.marketplace.entity.ProductTag;
import com.secureleaf.marketplace.entity.ProductTagId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductTagRepository extends JpaRepository<ProductTag, ProductTagId> {
}
