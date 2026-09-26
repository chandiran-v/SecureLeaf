package com.secureleaf.admin.repository;

import java.util.List;

/** Step 1+2 of the two-step paging recipe (see {@code ProductIdPage}/{@code ProductSearchRepositoryImpl}
 *  for the full rationale): an ordered id page plus its matching total count, no collection fetch. */
public record IdPage(List<Long> ids, long totalElements) {}
