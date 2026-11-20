package com.finora.user.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Nhân viên duyệt hồ sơ eKYC chờ duyệt tay; ghi chú tuỳ chọn.
 *
 * @param identity chỉ dùng cho hồ sơ AI gặp sự cố (không đọc được thẻ): nhân viên đọc ảnh thẻ và nhập các trường
 *                 định danh còn trống. Hồ sơ duyệt tay thông thường không được gửi trường này.
 */
public record EkycApproveRequest(
        @Size(max = 1000)
        String note,

        @Valid
        Identity identity
) {

    /** Khuôn cũ chỉ có ghi chú. */
    public EkycApproveRequest(String note) {
        this(note, null);
    }

    /**
     * Trường định danh nhân viên nhập theo ảnh thẻ; ngày theo {@code dd/mm/yyyy} như trên thẻ, giới tính "Nam"/"Nữ".
     * Chỉ điền trường đang trống trong bản nháp; trường đã có giá trị bị từ chối.
     *
     * @param cardType {@code CCCD_CHIP_2021 | CAN_CUOC_2024 | CCCD_CHIP_UNKNOWN | CCCD_BARCODE} (không nhận CMND)
     * @param noExpiry thẻ ghi "Không thời hạn" — khi đó bỏ trống {@code expiryDate}
     */
    public record Identity(
            @Size(max = 30) String cardType,
            @Size(max = 20) String idNumber,
            @Size(max = 100) String fullName,
            @Size(max = 10) String dateOfBirth,
            @Size(max = 10) String gender,
            @Size(max = 255) String address,
            @Size(max = 255) String placeOfOrigin,
            @Size(max = 255) String birthRegistrationPlace,
            @Size(max = 10) String issueDate,
            @Size(max = 10) String expiryDate,
            Boolean noExpiry
    ) {

        /** Không có trường nào được nhập (form rỗng gửi lên coi như không gửi). */
        public boolean hasNoValues() {
            return isBlank(cardType) && isBlank(idNumber) && isBlank(fullName) && isBlank(dateOfBirth)
                    && isBlank(gender) && isBlank(address) && isBlank(placeOfOrigin)
                    && isBlank(birthRegistrationPlace) && isBlank(issueDate) && isBlank(expiryDate)
                    && !Boolean.TRUE.equals(noExpiry);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
