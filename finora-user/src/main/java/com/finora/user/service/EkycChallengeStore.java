package com.finora.user.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.user.domain.LivenessStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phiên thử thách liveness trên Redis: {@code ekyc_challenge:<uid>} = {@code {sessionId, steps,
 * attemptId, issuedAt}}, TTL 90 giây.
 * <p>
 * Mỗi người dùng chỉ có một phiên: xin thử thách mới là ghi đè phiên cũ, nên không tích luỹ nhiều phiên
 * để thử lần lượt. Phiên được lấy ra bằng {@code GETDEL} — dùng đúng một lần kể cả khi sessionId gửi lên
 * sai, nên không thể dò sessionId trên cùng một thử thách. Phiên gắn với {@code attemptId} của bản nháp
 * để video không được tính cho lượt khác.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycChallengeStore {

    public static final Duration CHALLENGE_TTL = Duration.ofSeconds(90);

    private static final String KEY_CHALLENGE = "ekyc_challenge:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(UUID keycloakUserId, StoredChallenge challenge) {
        try {
            redisTemplate.opsForValue().set(KEY_CHALLENGE + keycloakUserId,
                    objectMapper.writeValueAsString(challenge), CHALLENGE_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được thử thách liveness", e);
        }
    }

    /** Lấy và huỷ phiên ({@code GETDEL}); rỗng nếu không có hoặc dữ liệu hỏng. */
    public Optional<StoredChallenge> consume(UUID keycloakUserId) {
        String raw = redisTemplate.opsForValue().getAndDelete(KEY_CHALLENGE + keycloakUserId);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, StoredChallenge.class));
        } catch (JsonProcessingException e) {
            log.warn("Thử thách liveness hỏng, bỏ qua: keycloakUserId={}", keycloakUserId);
            return Optional.empty();
        }
    }

    public void remove(UUID keycloakUserId) {
        redisTemplate.delete(KEY_CHALLENGE + keycloakUserId);
    }

    /**
     * @param issuedAt ISO-8601 UTC; lưu dạng chuỗi để không phụ thuộc cấu hình ngày giờ của ObjectMapper
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredChallenge(UUID sessionId, List<LivenessStep> steps, UUID attemptId, String issuedAt) {

        public Instant issuedAtInstant() {
            return Instant.parse(issuedAt);
        }
    }
}
