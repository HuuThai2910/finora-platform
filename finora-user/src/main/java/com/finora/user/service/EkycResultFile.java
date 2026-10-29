package com.finora.user.service;

import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycIdentityDraft;

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
