package com.corebank.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * A stable pagination envelope. Spring's own {@code Page} serialization is not part of its
 * API contract, so responses use this instead.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
