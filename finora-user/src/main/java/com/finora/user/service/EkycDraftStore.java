package com.finora.user.service;

import com.finora.user.domain.EkycIdentityDraft;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Giữ bản nháp eKYC trên Redis trong lúc người dùng làm liveness và soát thông tin.
 */
public interface EkycDraftStore {

    Duration DRAFT_TTL = Duration.ofMinutes(15);

    /** Lưu bản nháp, ghi đè bản cũ nếu người dùng quét lại. */
    void save(UUID keycloakUserId, EkycIdentityDraft draft);

    /** Đọc bản nháp; rỗng nếu chưa có, đã hết hạn hoặc không giải mã/đọc được (coi như hết hạn). */
    Optional<EkycIdentityDraft> find(UUID keycloakUserId);

    void remove(UUID keycloakUserId);
}
