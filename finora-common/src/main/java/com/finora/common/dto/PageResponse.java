package com.finora.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO phân trang chuẩn cho toàn bộ hệ thống.
 * Tất cả các API trả danh sách đều phải dùng DTO này.
 *
 * @param <T> Kiểu dữ liệu của từng phần tử trong danh sách
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;
}
