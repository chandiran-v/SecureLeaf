package com.secureleaf.admin.dto;

import java.util.List;

public record AdminActionPageDto(
        List<AdminActionDto> content,
        int totalElements,
        int totalPages,
        int pageNumber
) {}
