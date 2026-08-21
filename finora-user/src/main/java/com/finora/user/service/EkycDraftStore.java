package com.finora.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.user.dto.response.EkycResultResponse.EkycDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Giữ bản nháp eKYC trên Redis trong lúc người dùng soát thông tin.
 * <p>
 * Bản nháp phải nằm phía server: bước xác nhận chỉ gửi "đồng ý", client không
 * gửi lại dữ liệu — nếu không người dùng có thể sửa payload để bịa tên/số CCCD
 * khác với ảnh đã quét. TTL ngắn; hết hạn thì quét lại, không mất gì ngoài
 * một lần chụp.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycDraftStore {

    /** Đủ thời gian đọc soát; quá hạn coi như bỏ dở và phải quét lại. */
    public static final Duration DRAFT_TTL = Duration.ofMinutes(10);

    private static final String KEY_DRAFT = "ekyc_draft:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** Lưu bản nháp, ghi đè bản cũ nếu người dùng quét lại. */
    public void save(UUID keycloakUserId, EkycDraft draft) {
        try {
            redisTemplate.opsForValue().set(
                    KEY_DRAFT + keycloakUserId,
                    objectMapper.writeValueAsString(draft),
                    DRAFT_TTL);
        } catch (JsonProcessingException e) {
            // Không lưu được nháp thì bước xác nhận sẽ báo hết hạn — người dùng
            // quét lại; không được làm hỏng response của bước quét.
            log.error("Không serialize được bản nháp eKYC: userId={}", keycloakUserId, e);
        }
    }

    /** Đọc bản nháp; rỗng nếu chưa có, đã hết hạn hoặc dữ liệu hỏng. */
    public Optional<EkycDraft> find(UUID keycloakUserId) {
        String raw = redisTemplate.opsForValue().get(KEY_DRAFT + keycloakUserId);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, EkycDraft.class));
        } catch (JsonProcessingException e) {
            log.error("Bản nháp eKYC hỏng, bỏ qua: userId={}", keycloakUserId, e);
            return Optional.empty();
        }
    }

    /** Xoá bản nháp sau khi xác nhận thành công. */
    public void remove(UUID keycloakUserId) {
        redisTemplate.delete(KEY_DRAFT + keycloakUserId);
    }
}
