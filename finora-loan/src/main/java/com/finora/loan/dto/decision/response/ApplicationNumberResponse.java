package com.finora.loan.dto.decision.response;

/**
 * Cặp ID số và mã hồ sơ, cho web đổi "Hồ sơ #id" thành mã người đọc được và cho ô "Mở hồ sơ theo mã" đổi mã hiển thị
 * về mã tra cứu.
 *
 * @param applicationNumber mã tra cứu {@code LA-…}, khóa của mọi API hồ sơ
 * @param applicationCode   mã hiển thị {@code HS-yyyyMMdd-XXXX}
 * @param contractNumber    mã tra cứu {@code LC-…} của hợp đồng; chỉ có ở kết quả tra theo mã và khi đã lập hợp đồng
 * @param contractCode      mã hiển thị {@code HĐ-…} đi cùng {@code contractNumber}
 */
public record ApplicationNumberResponse(
        Long id,
        String applicationNumber,
        String applicationCode,
        String contractNumber,
        String contractCode
) {

    public ApplicationNumberResponse(Long id, String applicationNumber, String applicationCode) {
        this(id, applicationNumber, applicationCode, null, null);
    }
}
