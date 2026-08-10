package com.finora.loan.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Hạ tầng JSON/hash dùng chung cho idempotency và immutable evidence của Loan Service. */
@Component
public class HashingService {

    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalObjectMapper;

    public HashingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.canonicalObjectMapper = objectMapper.copy();
        this.canonicalObjectMapper.setConfig(this.canonicalObjectMapper.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
    }

    /** Serialize snapshot bằng ObjectMapper chung để giữ đúng contract JSON được lưu hoặc gửi đi. */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể tạo JSON cho request nội bộ", exception);
        }
    }

    /** Canonical hóa thứ tự field/key trước khi hash để thứ tự chèn Map không làm đổi digest. */
    public String sha256(Object value) {
        return sha256Text(toCanonicalJson(value));
    }

    private String toCanonicalJson(Object value) {
        try {
            return canonicalObjectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể tạo JSON canonical cho dữ liệu nội bộ", exception);
        }
    }

    /** Trả SHA-256 chữ thường dạng hexadecimal; không dùng hàm này để lưu mật khẩu. */
    public String sha256Text(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", exception);
        }
    }
}
