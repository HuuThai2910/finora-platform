package com.finora.user.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.user.service.EkycChallengeStore;
import com.finora.user.service.TemporaryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Phiên thử thách liveness trong kho tạm: {@code ekyc_challenge:<uid>} = {@code {sessionId, steps,
 * attemptId, issuedAt}}, TTL 90 giây.
 * <p>
 * Mỗi người dùng chỉ có một phiên: xin thử thách mới là ghi đè phiên cũ, nên không tích luỹ nhiều phiên
 * để thử lần lượt. Phiên được lấy ra bằng {@link TemporaryStore#take} (một câu {@code DELETE ... RETURNING},
 * tương đương {@code GETDEL}) — dùng đúng một lần kể cả khi sessionId gửi lên
 * sai, nên không thể dò sessionId trên cùng một thử thách. Phiên gắn với {@code attemptId} của bản nháp
 * để video không được tính cho lượt khác.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycChallengeStoreImpl implements EkycChallengeStore {

    private static final String KEY_CHALLENGE = "ekyc_challenge:";

    private final TemporaryStore temporaryStore;
    private final ObjectMapper objectMapper;

    @Override
    public void save(UUID keycloakUserId, StoredChallenge challenge) {
        try {
            temporaryStore.put(KEY_CHALLENGE + keycloakUserId,
                    objectMapper.writeValueAsString(challenge), CHALLENGE_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được thử thách liveness", e);
        }
    }

    @Override
    public Optional<StoredChallenge> consume(UUID keycloakUserId) {
        String raw = temporaryStore.take(KEY_CHALLENGE + keycloakUserId).orElse(null);
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
        temporaryStore.delete(KEY_CHALLENGE + keycloakUserId);
    }
}
