package com.finora.notification.service.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Đọc phần {@code data} của event thành record; lỗi định dạng là lỗi contract (DLT), không phải lỗi tạm thời. */
@Component
@RequiredArgsConstructor
public class EventPayloads {

    private final ObjectMapper mapper;

    public <T> T read(InboundEvent event, Class<T> type) {
        try {
            T value = mapper.treeToValue(event.data(), type);
            if (value == null) throw new IllegalArgumentException(event.eventType() + " thiếu data");
            return value;
        } catch (JsonProcessingException exception) {
            // Không đưa message của Jackson ra ngoài: nó có thể chứa một đoạn payload.
            throw new IllegalArgumentException(event.eventType() + " data không đúng contract");
        }
    }

    public String write(InboundEvent event) {
        try {
            return mapper.writeValueAsString(event.data());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(event.eventType() + " data không ghi lại được");
        }
    }

    public InboundEvent withData(InboundEvent template, String payloadJson) {
        try {
            return new InboundEvent(template.eventId(), template.eventType(), template.version(),
                    template.occurredAt(), template.topic(), mapper.readTree(payloadJson));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payload đã lưu của event chờ không đọc được: " + template.eventId());
        }
    }
}
