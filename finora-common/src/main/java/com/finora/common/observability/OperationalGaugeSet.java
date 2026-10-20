package com.finora.common.observability;

import java.util.List;
import java.util.Objects;

/**
 * Bộ gauge vận hành của một service. Mỗi service khai báo một bean loại này; {@link OperationalMonitoringScheduler}
 * đăng ký từng gauge với tag {@code service} và làm mới cả bộ theo {@code finora.operations.metrics.refresh-interval}.
 *
 * @param service tên ngắn của service (ví dụ {@code loan}), dùng làm tag để dashboard lọc theo service
 * @param gauges  danh sách chỉ số; tên + tag phải duy nhất trong cùng service
 */
public record OperationalGaugeSet(String service, List<OperationalGauge> gauges) {

    public OperationalGaugeSet {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("OperationalGaugeSet.service không được để trống");
        }
        service = service.trim();
        gauges = List.copyOf(Objects.requireNonNull(gauges, "gauges"));
    }
}
