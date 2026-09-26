package com.secureleaf.admin.dto;

import java.util.List;

public record AdminProductPageDto(
        List<AdminProductDto> content,
        int totalElements,
        int totalPages,
        int pageNumber
) {}
