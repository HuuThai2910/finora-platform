package com.finora.user.repository;

import com.finora.user.domain.EkycAttempt;

/** Một dòng trang duyệt eKYC: lượt xác minh kèm email chủ hồ sơ, lấy bằng một câu join. */
public record EkycAttemptSummaryRow(EkycAttempt attempt, String email) {
}
