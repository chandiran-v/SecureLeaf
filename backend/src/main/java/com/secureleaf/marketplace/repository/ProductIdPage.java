package com.secureleaf.marketplace.repository;

import java.util.List;

/** Step 1+2 result of the two-step paging recipe: an ordered id page plus the total count. */
public record ProductIdPage(List<Long> ids, long totalElements) {}
