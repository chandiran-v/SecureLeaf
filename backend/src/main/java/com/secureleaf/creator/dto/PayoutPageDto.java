package com.secureleaf.creator.dto;

import java.util.List;

public record PayoutPageDto(List<PayoutDto> content, int totalElements, int totalPages, int pageNumber) {}
