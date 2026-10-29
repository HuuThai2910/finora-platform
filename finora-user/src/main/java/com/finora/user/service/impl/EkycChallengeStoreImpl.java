package com.finora.user.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.user.service.EkycChallengeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
public class EkycChallengeStoreImpl implements EkycChallengeStore {

    private static final String KEY_CHALLENGE = "ekyc_challenge:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void save(UUID keycloakUserId, StoredChallenge challenge) {
        try {
            redisTemplate.opsForValue().set(KEY_CHALLENGE + keycloakUserId,
                    objectMapper.writeValueAsString(challenge), CHALLENGE_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được thử thách liveness", e);
        }
    }

    @Override
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

    @Override
    public void remove(UUID keycloakUserId) {
        redisTemplate.delete(KEY_CHALLENGE + keycloakUserId);
    }
}
