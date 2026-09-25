package com.finora.loan.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình allowlist ánh xạ đúng event/version sang topic đã được hai owner duyệt. */
@ConfigurationProperties(prefix = "finora.loan.kafka")
public record KafkaPublisherProperties(
        Duration publishTimeout,
        List<EventRoute> routes
) {

    private static final Pattern TOPIC_PATTERN = Pattern.compile("finora\\.loan\\.[a-z0-9][a-z0-9.-]*");

    public KafkaPublisherProperties {
        publishTimeout = publishTimeout == null ? Duration.ofSeconds(10) : publishTimeout;
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (publishTimeout.isZero() || publishTimeout.isNegative() || publishTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("kafka.publishTimeout phải từ trên 0 đến 1 phút");
        }
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("Phải cấu hình ít nhất một Kafka event route khi chọn transport kafka");
        }
        Map<EventRouteKey, String> uniqueRoutes = new HashMap<>();
        for (EventRoute route : routes) {
            Objects.requireNonNull(route, "Kafka event route không được null");
            EventRouteKey key = route.key();
            if (uniqueRoutes.putIfAbsent(key, route.topic()) != null) {
                throw new IllegalArgumentException("Kafka event route bị trùng: " + key);
            }
        }
    }

    public String topicFor(String eventType, int eventVersion) {
        return routes.stream()
                .filter(route -> route.eventType().equals(eventType) && route.eventVersion() == eventVersion)
                .map(EventRoute::topic)
                .findFirst()
                .orElse(null);
    }

    public record EventRoute(String eventType, int eventVersion, String topic) {

        public EventRoute {
            eventType = requireText(eventType, "eventType");
            topic = requireText(topic, "topic");
            if (eventVersion < 1) {
                throw new IllegalArgumentException("Kafka eventVersion phải dương");
            }
            if (!TOPIC_PATTERN.matcher(topic).matches() || topic.length() > 249) {
                throw new IllegalArgumentException(
                        "Kafka topic phải theo mẫu finora.loan.<past-event> và không vượt 249 ký tự");
            }
        }

        private EventRouteKey key() {
            return new EventRouteKey(eventType, eventVersion);
        }
    }

    private record EventRouteKey(String eventType, int eventVersion) {
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }
}
