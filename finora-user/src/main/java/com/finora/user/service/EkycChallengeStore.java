package com.finora.user.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finora.user.domain.LivenessStep;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phiên thử thách liveness trên Redis: {@code ekyc_challenge:<uid>} = {@code {sessionId, steps,
 * attemptId, issuedAt}}, TTL 90 giây.
 */
public interface EkycChallengeStore {

    Duration CHALLENGE_TTL = Duration.ofSeconds(90);

    void save(UUID keycloakUserId, StoredChallenge challenge);

    /** Lấy và huỷ phiên ({@code GETDEL}); rỗng nếu không có hoặc dữ liệu hỏng. */
    Optional<StoredChallenge> consume(UUID keycloakUserId);

    void remove(UUID keycloakUserId);

    /**
     * @param issuedAt ISO-8601 UTC; lưu dạng chuỗi để không phụ thuộc cấu hình ngày giờ của ObjectMapper
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredChallenge(UUID sessionId, List<LivenessStep> steps, UUID attemptId, String issuedAt) {

        public Instant issuedAtInstant() {
            return Instant.parse(issuedAt);
        }
    }
}
