package com.finora.loan.service.application;

import java.util.Collection;
import java.util.Map;

/**
 * Tra mã hồ sơ ({@code LA-…}) theo ID số, để các màn vận hành (saga giải ngân, sự kiện trả nợ chờ ghép, bảng đối soát của
 * Payment) hiện mã người đọc được thay cho "Hồ sơ #id".
 */
public interface ApplicationNumberLookupService {

    /** Số ID tối đa một lần tra. */
    int MAX_IDS = 100;

    /** ID không tồn tại thì không có trong kết quả. */
    Map<Long, String> numbersOf(Collection<Long> applicationIds);
}
