package com.finora.user.service;

import com.finora.user.dto.response.UserFullNameResponse;
import com.finora.user.dto.response.UserIdMatchResponse;
import com.finora.user.dto.response.UserIdentityResponse;

import java.util.Collection;
import java.util.List;

/**
 * Tra họ tên và CCCD đã che của một người dùng cho service nội bộ (giấy xác nhận chuyển nhượng Note của Investment).
 */
public interface UserIdentityService {

    UserIdentityResponse identityOf(String userId);

    /**
     * Họ tên của nhiều người dùng trong một lần đọc, để danh sách của service khác không gọi từng dòng.
     *
     * <p>Nhận cùng hai dạng ID như {@link #identityOf(String)}. ID không tìm thấy hoặc sai dạng bị bỏ qua thay vì
     * báo lỗi cả lô; kết quả giữ thứ tự ID gửi lên, mỗi ID một dòng.</p>
     */
    List<UserFullNameResponse> fullNamesOf(Collection<String> userIds);

    /**
     * Người dùng có họ tên chứa {@code query}, không phân biệt dấu và hoa thường, tối đa {@code limit} người (ID nhỏ
     * trước). Chuỗi tìm rỗng hoặc chỉ một ký tự trả danh sách rỗng.
     */
    List<UserIdMatchResponse> userIdsByName(String query, int limit);
}
