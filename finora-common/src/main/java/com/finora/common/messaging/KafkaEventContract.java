package com.finora.common.messaging;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Hợp đồng consumer chấp nhận cho một loại event: loại, các version được nhận và mức kiểm header/envelope.
 * {@link KafkaEventReader} dựa vào đây để trả event hợp lệ hoặc ném {@link IllegalArgumentException}.
 * <p>
 * {@link #of} cho mức chặt mà đa số consumer đang dùng: header {@code finora-event-*} không bắt buộc nhưng có thì phải
 * khớp envelope; envelope phải có {@code eventId}, {@code occurredAt} (ISO-8601) và {@code data}. Các {@code with*} chỉ
 * để giữ đúng hành vi riêng của vài consumer (Notification, Blockchain, hai consumer servicing của Payment); cách dùng
 * cho từng consumer ở plan REFACTOR-001, mục "Bộ Kafka dùng chung — cách dùng".
 *
 * @param eventType       loại event mong đợi; header {@code finora-event-type} (nếu có) phải bằng giá trị này
 * @param versions        các version envelope được nhận; rỗng là nhận mọi version từ 1 trở lên
 * @param headerRule      mức kiểm header
 * @param eventIdRequired envelope bắt buộc có {@code eventId}
 * @param occurredAtRule  cách kiểm {@code occurredAt}
 * @param dataRule        cách kiểm {@code data}
 */
public record KafkaEventContract(
        String eventType,
        Set<Integer> versions,
        HeaderRule headerRule,
        boolean eventIdRequired,
        OccurredAtRule occurredAtRule,
        DataRule dataRule
) {

    public KafkaEventContract {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType của KafkaEventContract không được để trống");
        }
        versions = versions == null ? Set.of() : Set.copyOf(versions);
        for (int version : versions) {
            if (version < 1) {
                throw new IllegalArgumentException("Version của " + eventType + " phải từ 1 trở lên: " + version);
            }
        }
        Objects.requireNonNull(headerRule, "headerRule");
        Objects.requireNonNull(occurredAtRule, "occurredAtRule");
        Objects.requireNonNull(dataRule, "dataRule");
    }

    /** Mức chặt chuẩn cho {@code eventType} với đúng các version liệt kê. */
    public static KafkaEventContract of(String eventType, int version, int... otherVersions) {
        Set<Integer> versions = new LinkedHashSet<>();
        versions.add(version);
        for (int otherVersion : otherVersions) {
            versions.add(otherVersion);
        }
        return new KafkaEventContract(eventType, versions, HeaderRule.IF_PRESENT, true, OccurredAtRule.REQUIRED,
                DataRule.REQUIRED);
    }

    /**
     * Mức chặt chuẩn nhưng nhận mọi version từ 1 trở lên (consumer chung như Notification); header version nếu có phải
     * bằng version của envelope.
     */
    public static KafkaEventContract anyVersion(String eventType) {
        return new KafkaEventContract(eventType, Set.of(), HeaderRule.IF_PRESENT, true, OccurredAtRule.REQUIRED,
                DataRule.REQUIRED);
    }

    public KafkaEventContract withHeaderRule(HeaderRule headerRule) {
        return new KafkaEventContract(eventType, versions, headerRule, eventIdRequired, occurredAtRule, dataRule);
    }

    public KafkaEventContract withEventIdRequired(boolean eventIdRequired) {
        return new KafkaEventContract(eventType, versions, headerRule, eventIdRequired, occurredAtRule, dataRule);
    }

    public KafkaEventContract withOccurredAtRule(OccurredAtRule occurredAtRule) {
        return new KafkaEventContract(eventType, versions, headerRule, eventIdRequired, occurredAtRule, dataRule);
    }

    public KafkaEventContract withDataRule(DataRule dataRule) {
        return new KafkaEventContract(eventType, versions, headerRule, eventIdRequired, occurredAtRule, dataRule);
    }

    /** Version của envelope có được nhận không. */
    public boolean acceptsVersion(int version) {
        return versions.isEmpty() ? version >= 1 : versions.contains(version);
    }

    /** Mức kiểm header {@code finora-event-*}. Giá trị header được bỏ khoảng trắng hai đầu trước khi so. */
    public enum HeaderRule {

        /**
         * Không bắt buộc; header nào có thì phải khớp: id là UUID bằng {@code eventId}, type bằng
         * {@link KafkaEventContract#eventType()}, version là số nguyên bằng {@code version} của envelope.
         */
        IF_PRESENT,

        /** Như {@link #IF_PRESENT} nhưng ba header id, type, version bắt buộc phải có. */
        REQUIRED,

        /** Chỉ kiểm header type (nếu có), kiểm trước khi đọc JSON; bỏ qua header id và version. */
        TYPE_ONLY
    }

    /** Cách kiểm {@code occurredAt}. ISO-8601 nghĩa là đọc được bằng {@code Instant} hoặc {@code OffsetDateTime}. */
    public enum OccurredAtRule {

        /** Bắt buộc có và là ISO-8601. */
        REQUIRED,

        /** Thiếu hoặc JSON null thì {@link KafkaEvent#occurredAt()} là {@code null}; có thì phải là ISO-8601. */
        OPTIONAL,

        /** Không đọc, không kiểm; {@link KafkaEvent#occurredAt()} luôn là {@code null}. */
        IGNORED
    }

    /** Cách kiểm {@code data} của envelope. */
    public enum DataRule {

        /** Bắt buộc có và khác JSON null. */
        REQUIRED,

        /** Bắt buộc là JSON object. */
        OBJECT,

        /** Không kiểm; thiếu hoặc JSON null thì {@code data} sau khi đổi kiểu là {@code null}. */
        OPTIONAL
    }
}
