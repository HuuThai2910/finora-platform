package com.finora.common.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Một chỉ số vận hành mà service khai báo (W3E, D58): tên metric chung toàn hệ thống, tag phân loại và hàm đọc giá trị.
 *
 * <p>Hàm {@code reader} chạy trên luồng làm mới của {@link OperationalMonitoringScheduler}, không chạy khi scrape. Nó
 * được phép truy vấn database của chính service (câu đếm có trần, có timeout) nhưng không được gọi service khác.</p>
 *
 * @param name        tên metric Micrometer, ví dụ {@value #INCIDENTS}
 * @param description mô tả hiện trong {@code /actuator/metrics/<name>}
 * @param baseUnit    đơn vị (ví dụ {@code events}, {@code seconds})
 * @param tags        tag riêng của chỉ số; tag {@code service} do {@link OperationalGaugeSet} thêm
 * @param reader      hàm đọc giá trị; ném exception thì gauge báo NaN cho tới lần đọc thành công kế tiếp
 */
public record OperationalGauge(
        String name,
        String description,
        String baseUnit,
        Map<String, String> tags,
        DoubleSupplier reader
) {

    /** Số event outbox theo trạng thái ({@code status} = {@code pending} hoặc {@code dead_letter}). */
    public static final String OUTBOX_EVENTS = "finora.outbox.events";
    /**
     * Số giây kể từ lúc tạo của event outbox chưa gửi được cũ nhất (chờ gửi, chờ retry hoặc đang gửi): độ trễ thật mà
     * consumer phải chịu, 0 khi không còn event chờ. Đếm từ lúc tạo chứ không từ hạn retry, vì mỗi lần gửi lỗi relay
     * dời hạn retry về sau nên "tuổi tính từ hạn" không bao giờ vượt backoff dù Kafka tắt cả giờ.
     */
    public static final String OUTBOX_OLDEST_PENDING_AGE = "finora.outbox.oldest.pending.age";
    /** Số bản ghi đang cần vận hành xử lý theo loại sự cố ({@code type}). */
    public static final String INCIDENTS = "finora.operations.incidents";

    public OperationalGauge {
        name = requireText(name, "name");
        description = requireText(description, "description");
        baseUnit = requireText(baseUnit, "baseUnit");
        tags = Map.copyOf(Objects.requireNonNull(tags, "tags"));
        Objects.requireNonNull(reader, "reader");
    }

    public static OperationalGauge outboxEvents(String status, DoubleSupplier reader) {
        return new OperationalGauge(OUTBOX_EVENTS,
                "Số event outbox của service theo trạng thái (đếm có trần finora.operations.metrics.count-cap)",
                "events", Map.of("status", requireText(status, "status")), reader);
    }

    public static OperationalGauge outboxOldestPendingAge(DoubleSupplier reader) {
        return new OperationalGauge(OUTBOX_OLDEST_PENDING_AGE,
                "Số giây kể từ lúc tạo của event outbox chưa gửi được cũ nhất; 0 khi không còn event chờ",
                "seconds", Map.of(), reader);
    }

    public static OperationalGauge incidents(String type, DoubleSupplier reader) {
        return new OperationalGauge(INCIDENTS,
                "Số bản ghi đang chờ vận hành xử lý theo loại sự cố (đếm có trần finora.operations.metrics.count-cap)",
                "records", Map.of("type", requireText(type, "type")), reader);
    }

    /** Tag đầy đủ, thứ tự ổn định: {@code service} trước, sau đó tag riêng theo tên. */
    Map<String, String> tagsWithService(String service) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("service", requireText(service, "service"));
        tags.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> all.put(entry.getKey(), entry.getValue()));
        return all;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OperationalGauge." + field + " không được để trống");
        }
        return value.trim();
    }
}
