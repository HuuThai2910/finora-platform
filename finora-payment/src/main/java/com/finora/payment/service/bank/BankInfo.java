package com.finora.payment.service.bank;

/**
 * Một ngân hàng trong danh bạ NAPAS.
 *
 * @param code      mã VietQR (ví dụ {@code VCB}, {@code ICB}), là mã app gửi lên và lưu trong lệnh rút
 * @param bin       mã BIN 6 số NAPAS, dùng khi nối provider chuyển khoản thật
 * @param shortName tên ngắn hiển thị (ví dụ {@code Vietcombank})
 * @param name      tên pháp lý đầy đủ
 * @param logo      URL logo trên CDN của VietQR
 */
public record BankInfo(String code, String bin, String shortName, String name, String logo) {
}
