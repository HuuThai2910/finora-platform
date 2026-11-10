package com.finora.investment.service;

import java.util.Collection;
import java.util.Map;

/**
 * Họ tên người dùng theo ID, đọc từ User theo lô và giữ trong bộ nhớ một thời gian ngắn: người vay ở danh sách gọi vốn,
 * hai bên mua bán và người nắm giữ phần vốn ở Chợ chuyển nhượng.
 */
public interface BorrowerNameLookup {

    /**
     * Họ tên của các người vay trên một trang danh sách.
     *
     * <p>Không ném lỗi: User lỗi hay chậm thì người chưa có trong bộ nhớ đệm vắng mặt trong kết quả và danh sách hiện
     * dòng không tên, thay vì cả trang gọi vốn không mở được vì thiếu một dòng chữ.</p>
     *
     * @return họ tên theo ID người vay; người không có họ tên hoặc không đọc được thì không có trong map
     */
    Map<String, String> fullNamesOf(Collection<String> borrowerIds);
}
