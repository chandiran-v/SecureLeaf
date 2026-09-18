package com.secureleaf.marketplace.entity;

import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductTagId implements Serializable {

    private Long product;
    private String tag;

    public ProductTagId() {}

    public ProductTagId(Long product, String tag) {
        this.product = product;
        this.tag = tag;
    }

@Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProductTagId that = (ProductTagId) o;
        return Objects.equals(product, that.product) && Objects.equals(tag, that.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(product, tag);
    }
}
