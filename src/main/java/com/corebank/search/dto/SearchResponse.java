package com.corebank.search.dto;

import java.util.List;

/** Pagination envelope for search results -- not {@code PagedResponse}, which is built from a
 *  Spring Data {@code Page} that OpenSearch results never produce. */
public record SearchResponse<T>(List<T> hits, long totalHits, int page, int size) {
}
