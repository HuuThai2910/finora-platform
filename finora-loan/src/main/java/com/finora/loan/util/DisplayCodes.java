package com.finora.loan.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Mã hiển thị dễ đọc của hồ sơ vay ({@code HS-yyyyMMdd-XXXX}) và hợp đồng ({@code HĐ-yyyyMMdd-XXXX}).
 *
 * <p>Ngày lấy theo giờ Việt Nam lúc tạo để người dùng nhìn mã là biết ngày nộp/lập. Bốn ký tự cuối chọn bằng
 * {@link SecureRandom} từ bảng chữ đã bỏ các ký tự dễ đọc nhầm (0/O, 1/I/L, U/V), nên đọc qua điện thoại hay chép tay không
 * lẫn. Mã chỉ để hiển thị và tra cứu nội bộ: nó lộ ngày tạo nên khóa API và khóa event vẫn là mã ngẫu nhiên dài
 * ({@code LA-}/{@code LC-}).</p>
 *
 * <p>Hợp đồng dùng chữ {@code Đ} như cách gọi quen thuộc; mã không nằm trong URL hay tên tệp, font PDF (DejaVu) có chữ
 * này, và ô tìm kiếm chấp nhận cả {@code HD-} qua {@link #normalize(String)} cho người gõ không dấu.</p>
 */
public final class DisplayCodes {

    public static final String APPLICATION_PREFIX = "HS";
    public static final String CONTRACT_PREFIX = "HĐ";
    /** Migration V39 dùng đúng bảng chữ này khi backfill dữ liệu cũ. */
    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTWXYZ23456789";
    public static final int RANDOM_LENGTH = 4;
    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private DisplayCodes() {
    }

    /** Mã hồ sơ mới, ví dụ {@code HS-20261109-K7QM}. */
    public static String application(Instant createdAt) {
        return generate(APPLICATION_PREFIX, createdAt, SECURE_RANDOM);
    }

    /** Mã hợp đồng mới, ví dụ {@code HĐ-20261109-3RXA}. */
    public static String contract(Instant createdAt) {
        return generate(CONTRACT_PREFIX, createdAt, SECURE_RANDOM);
    }

    /** Tách nguồn ngẫu nhiên để test kiểm được định dạng; code chạy thật luôn đi qua {@link SecureRandom}. */
    static String generate(String prefix, Instant createdAt, RandomGenerator random) {
        Objects.requireNonNull(createdAt, "createdAt");
        StringBuilder code = new StringBuilder(prefix).append('-')
                .append(DAY.format(createdAt.atZone(VIETNAM))).append('-');
        for (int index = 0; index < RANDOM_LENGTH; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * Chuẩn hóa chuỗi người dùng gõ khi tra cứu: bỏ khoảng trắng hai đầu, viết hoa, {@code HD-} thành {@code HĐ-}.
     * Trả {@code null} khi chuỗi rỗng.
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String value = input.trim().toUpperCase(Locale.ROOT);
        return value.startsWith("HD-") ? CONTRACT_PREFIX + value.substring(2) : value;
    }
}
