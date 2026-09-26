package com.secureleaf.admin.dto;

import java.util.List;

public record AdminUserPageDto(
        List<AdminUserDto> content,
        int totalElements,
        int totalPages,
        int pageNumber
) {}
