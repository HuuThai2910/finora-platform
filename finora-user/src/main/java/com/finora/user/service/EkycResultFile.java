package com.finora.user.service;

import com.finora.user.domain.entity.EkycAttempt;
import com.finora.user.domain.entity.EkycEvidence;
import com.finora.user.domain.model.EkycIdentityDraft;

import java.time.Instant;
import java.util.List;

/**
 * Dựng {@code result.json} — bản tóm tắt bất biến của một lượt đã có kết quả cuối, lưu cạnh ảnh/video.
 */
public interface EkycResultFile {

    /**
     * @param outcome {@code VERIFIED} hoặc {@code MANUAL_REVIEW}
     * @param files   bằng chứng còn trên kho của lượt (trước khi thêm chính file này)
     */
    byte[] build(EkycAttempt attempt, EkycIdentityDraft draft, String outcome, List<EkycEvidence> files,
                 Instant decidedAt);
}
