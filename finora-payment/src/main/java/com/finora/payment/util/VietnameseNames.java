package com.finora.payment.util;

import java.text.Normalizer;
import java.util.Locale;

/** Chuẩn hóa họ tên tiếng Việt về dạng ngân hàng in trên tài khoản: IN HOA, không dấu, một khoảng trắng. */
public final class VietnameseNames {

    private VietnameseNames() {
    }

    /** {@code "Nguyễn  Văn Đức"} → {@code "NGUYEN VAN DUC"}. {@code Đ/đ} không tách được bằng NFD nên đổi tay. */
    public static String bankStyle(String name) {
        if (name == null) {
            return "";
        }
        String plain = Normalizer.normalize(name.replace('Đ', 'D').replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return plain.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]+", " ").trim().replaceAll("\\s+", " ");
    }

    /** Hai cách viết cùng một họ tên (có/không dấu, hoa/thường, thừa khoảng trắng) có khớp nhau không. */
    public static boolean sameName(String left, String right) {
        String a = bankStyle(left);
        return !a.isEmpty() && a.equals(bankStyle(right));
    }
}
