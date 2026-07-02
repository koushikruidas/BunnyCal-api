package io.bunnycal.admin.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Standard paginated envelope for admin list endpoints. Returned inside
 * {@code ApiResponse<PageResponse<T>>}. Defined once so every admin module paginates the
 * same way.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /** Maps a Spring Data {@link Page} of entities to a PageResponse of DTOs. */
    public static <E, D> PageResponse<D> of(Page<E> page, Function<E, D> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
