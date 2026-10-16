package com.finora.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.support.CryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Giữ bản nháp eKYC trên Redis trong lúc người dùng làm liveness và soát thông tin.
 * <p>
 * Bản nháp phải nằm phía server: bước xác nhận không nhận lại dữ liệu từ client, nếu không người dùng
 * có thể sửa payload để bịa số định danh khác với ảnh đã quét. Giá trị được mã hoá AES-GCM bằng khoá
 * PII của service vì chứa số định danh, họ tên và embedding khuôn mặt (dữ liệu sinh trắc) — Redis local
 * thường không đặt mật khẩu.
 * <p>
 * TTL 15 phút, làm mới mỗi lần ghi (sau mỗi lần gửi video) để người dùng đang thao tác không bị mất
 * bản nháp giữa chừng; bỏ dở quá hạn thì quét lại thẻ.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycDraftStore {

    public static final Duration DRAFT_TTL = Duration.ofMinutes(15);

    private static final String KEY_DRAFT = "ekyc_draft:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CryptoProperties cryptoProperties;

    /** Lưu bản nháp, ghi đè bản cũ nếu người dùng quét lại. */
    public void save(UUID keycloakUserId, EkycIdentityDraft draft) {
        String json;
        try {
            json = objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            // Lỗi lập trình (kiểu dữ liệu không serialize được); không nuốt lỗi vì client sẽ nhận
            // DRAFT_READY trong khi bước sau chắc chắn báo hết hạn.
            throw new IllegalStateException("Không serialize được bản nháp eKYC", e);
        }
        redisTemplate.opsForValue().set(
                KEY_DRAFT + keycloakUserId,
                CryptoUtils.encryptAesGcm(json, cryptoProperties.getAesSecret()),
                DRAFT_TTL);
    }

    /** Đọc bản nháp; rỗng nếu chưa có, đã hết hạn hoặc không giải mã/đọc được (coi như hết hạn). */
    public Optional<EkycIdentityDraft> find(UUID keycloakUserId) {
        String raw = redisTemplate.opsForValue().get(KEY_DRAFT + keycloakUserId);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            String json = CryptoUtils.decryptAesGcm(raw, cryptoProperties.getAesSecret());
            return Optional.of(objectMapper.readValue(json, EkycIdentityDraft.class));
        } catch (JsonProcessingException | IllegalStateException e) {
            // Đổi khoá AES hoặc đổi khuôn bản nháp giữa hai phiên bản: người dùng chỉ phải quét lại.
            log.warn("Bản nháp eKYC không đọc được, bỏ qua: keycloakUserId={}, cause={}",
                    keycloakUserId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void remove(UUID keycloakUserId) {
        redisTemplate.delete(KEY_DRAFT + keycloakUserId);
    }
}
