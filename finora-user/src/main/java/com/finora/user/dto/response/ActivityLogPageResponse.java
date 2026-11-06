package com.finora.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Một trang nhật ký hoạt động, mới nhất trước, kèm số đếm của cả khoảng thời gian cho ô tổng quan. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityLogPageResponse {

    private List<ActivityLogEntryResponse> content;
    /** Số lượt gọi khớp bộ lọc. */
    private long totalElements;
    private int page;
    private int size;
    /** Số lượt gọi sâu nhất xem được qua phân trang. */
    private int maxRows;
    /** Tổng lượt gọi trong khoảng thời gian (không lọc). */
    private long total;
    private long clientErrors;
    private long serverErrors;
}
