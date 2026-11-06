package com.finora.user.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.util.CryptoUtils;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.model.EkycIdentityDraft;
import com.finora.user.service.EkycDraftStore;
import com.finora.user.service.TemporaryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Giữ bản nháp eKYC trong kho tạm ({@link TemporaryStore}, key {@code ekyc_draft:<uid>}) trong lúc người dùng
 * làm liveness và soát thông tin.
 * <p>
 * Bản nháp phải nằm phía server: bước xác nhận không nhận lại dữ liệu từ client, nếu không người dùng
 * có thể sửa payload để bịa số định danh khác với ảnh đã quét. Giá trị được mã hoá AES-GCM bằng khoá
 * PII của service vì chứa số định danh, họ tên và embedding khuôn mặt (dữ liệu sinh trắc) — người đọc được
 * bảng trạng thái tạm (bản sao lưu, quyền đọc database) vẫn không thấy dữ liệu rõ. Worker dọn kho tạm xoá hẳn
 * bản nháp quá hạn.
 * <p>
 * TTL 15 phút, làm mới mỗi lần ghi (sau mỗi lần gửi video) để người dùng đang thao tác không bị mất
 * bản nháp giữa chừng; bỏ dở quá hạn thì quét lại thẻ.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycDraftStoreImpl implements EkycDraftStore {

    private static final String KEY_DRAFT = "ekyc_draft:";

    private final TemporaryStore temporaryStore;
    private final ObjectMapper objectMapper;
    private final CryptoProperties cryptoProperties;

    @Override
    public void save(UUID keycloakUserId, EkycIdentityDraft draft) {
        String json;
        try {
            json = objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            // Lỗi lập trình (kiểu dữ liệu không serialize được); không nuốt lỗi vì client sẽ nhận
            // DRAFT_READY trong khi bước sau chắc chắn báo hết hạn.
            throw new IllegalStateException("Không serialize được bản nháp eKYC", e);
        }
        temporaryStore.put(
                KEY_DRAFT + keycloakUserId,
                CryptoUtils.encryptAesGcm(json, cryptoProperties.getAesSecret()),
                DRAFT_TTL);
    }

    @Override
    public Optional<EkycIdentityDraft> find(UUID keycloakUserId) {
        String raw = temporaryStore.find(KEY_DRAFT + keycloakUserId).orElse(null);
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

    @Override
    public void remove(UUID keycloakUserId) {
        temporaryStore.delete(KEY_DRAFT + keycloakUserId);
    }
}
