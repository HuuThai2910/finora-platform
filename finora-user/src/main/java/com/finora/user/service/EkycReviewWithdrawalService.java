package com.finora.user.service;

import com.finora.user.dto.response.MyEkycStatusResponse;

import java.util.UUID;

/**
 * Người dùng rút lại hồ sơ eKYC đang chờ nhân viên duyệt tay để gửi hồ sơ mới.
 */
public interface EkycReviewWithdrawalService {

    /**
     * Rút lại lượt mới nhất khi lượt đó đang {@code MANUAL_REVIEW} và nhân viên chưa quyết định: lượt
     * {@code WITHDRAWN}, hồ sơ về {@code PENDING} (chưa xác minh), claim Keycloak cập nhật, bằng chứng của lượt bị
     * xoá như lượt bỏ dở. Đồng ý eKYC giữ nguyên để người dùng làm lại ngay.
     * <p>
     * Không có hồ sơ chờ duyệt → {@code 409 EKYC_NOT_WITHDRAWABLE}; nhân viên vừa duyệt/từ chối (kể cả cùng lúc)
     * → {@code 409 ATTEMPT_ALREADY_DECIDED}.
     *
     * @return trạng thái eKYC sau khi rút
     */
    MyEkycStatusResponse withdraw(UUID keycloakUserId);
}
