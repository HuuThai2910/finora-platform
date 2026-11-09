package com.finora.loan.service.application;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.dto.decision.response.ApplicationNumberResponse;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Tra mã hồ sơ ({@code LA-…}) theo ID số, để các màn vận hành (saga giải ngân, sự kiện trả nợ chờ ghép, bảng đối soát của
 * Payment) hiện mã người đọc được thay cho "Hồ sơ #id"; và đổi mã nhân viên gõ vào ô tìm kiếm về mã tra cứu.
 */
public interface ApplicationNumberLookupService {

    /** Số ID tối đa một lần tra. */
    int MAX_IDS = 100;

    /** ID không tồn tại thì không có trong kết quả. */
    Map<Long, String> numbersOf(Collection<Long> applicationIds);

    /** Mã tra cứu và mã hiển thị của tối đa {@link #MAX_IDS} hồ sơ; ID không tồn tại thì không có trong kết quả. */
    List<ApplicationNumberResponse> referencesOf(Collection<Long> applicationIds);

    /**
     * Hồ sơ ứng với một mã bất kỳ nhân viên có trong tay: mã hiển thị hồ sơ {@code HS-…}, mã hiển thị hợp đồng
     * {@code HĐ-…} (gõ không dấu {@code HD-…} cũng nhận), mã tra cứu {@code LA-…} hoặc {@code LC-…}. Kết quả kèm cặp mã
     * hợp đồng khi hồ sơ đã có hợp đồng.
     *
     * @throws ResourceNotFoundException khi không có hồ sơ nào ứng với mã
     */
    ApplicationNumberResponse resolve(String code);
}
