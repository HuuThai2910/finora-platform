package com.finora.loan.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

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
