package com.finora.user.service;

import com.finora.user.dto.response.UserIdentityResponse;

/**
 * Tra họ tên và CCCD đã che của một người dùng cho service nội bộ (giấy xác nhận chuyển nhượng Note của Investment).
 */
public interface UserIdentityService {

    UserIdentityResponse identityOf(String userId);
}
