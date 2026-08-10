package com.finora.loan.dto.common;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageResponse<T>(
        List<T> data,
        int page,
        int size,
        long totalElements
) {
    public static <T> PageResponse<T> from(Page<T> source) {
        return new PageResponse<>(source.getContent(), source.getNumber(), source.getSize(), source.getTotalElements());
    }
}
