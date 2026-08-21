package com.finora.user.util;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Đối chiếu dữ liệu OCR đọc từ ảnh CCCD với dữ liệu người dùng đã khai.
 * <p>
 * Chỉ số CCCD là điều kiện chặn (so bằng HMAC ở tầng service). Họ tên và ngày
 * sinh chỉ sinh cảnh báo: EasyOCR trên CCCD tiếng Việt rất hay sai dấu và nhầm
 * ký tự, nếu chặn cứng thì tỉ lệ người dùng thật bị từ chối sẽ cao vô lý.
 */
public final class CccdMatcher {

    /** Họ tên trên ảnh khác họ tên trong hồ sơ. */
    public static final String WARNING_FULL_NAME_MISMATCH = "FULL_NAME_MISMATCH";

    /** Ngày sinh trên ảnh khác ngày sinh trong hồ sơ. */
    public static final String WARNING_DOB_MISMATCH = "DOB_MISMATCH";

    /**
     * Các định dạng ngày mà OCR đọc được từ phôi CCCD.
     * <p>
     * Dùng {@link ResolverStyle#STRICT}: chế độ mặc định sẽ tự nắn "31/02/2000"
     * thành 29/02/2000, biến một lần OCR sai thành một ngày sinh hợp lệ nhưng
     * sai — rồi sinh cảnh báo lệch ngày sinh không có thật.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT));

    private CccdMatcher() {
        // Lớp tiện ích — không cho phép khởi tạo instance
    }

    /**
     * Chuẩn hoá họ tên để so sánh: bỏ dấu, viết hoa, gộp khoảng trắng.
     * <p>
     * "Nguyễn Văn A" và "NGUYEN VAN A" phải được coi là một, vì dấu tiếng Việt
     * là thứ OCR sai nhiều nhất. Chữ Đ/đ phải xử lý riêng do không tách được
     * dấu bằng chuẩn hoá Unicode NFD.
     */
    public static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String withoutMarks = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('Đ', 'D')
                .replace('đ', 'd');
        return withoutMarks.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Đọc ngày sinh OCR trả về; rỗng nếu không khớp định dạng nào. */
    public static Optional<LocalDate> parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(raw.trim(), format));
            } catch (DateTimeParseException ignored) {
                // Thử định dạng tiếp theo — OCR có thể trả dấu gạch hoặc dấu chéo
            }
        }
        return Optional.empty();
    }

    /**
     * Sinh cảnh báo cho các trường mềm. Trường nào OCR không đọc được thì bỏ
     * qua, vì "không đọc được" không phải bằng chứng của "sai".
     *
     * @param profileFullName    họ tên trong hồ sơ
     * @param profileDateOfBirth ngày sinh trong hồ sơ
     * @param ocrFullName        họ tên OCR đọc được, có thể {@code null}
     * @param ocrDateOfBirth     ngày sinh OCR đọc được dạng chuỗi, có thể {@code null}
     */
    public static List<String> softFieldWarnings(
            String profileFullName,
            LocalDate profileDateOfBirth,
            String ocrFullName,
            String ocrDateOfBirth) {

        List<String> warnings = new ArrayList<>();

        if (ocrFullName != null && !ocrFullName.isBlank() && profileFullName != null
                && !normalizeName(ocrFullName).equals(normalizeName(profileFullName))) {
            warnings.add(WARNING_FULL_NAME_MISMATCH);
        }

        Optional<LocalDate> ocrDate = parseDate(ocrDateOfBirth);
        if (ocrDate.isPresent() && profileDateOfBirth != null
                && !ocrDate.get().equals(profileDateOfBirth)) {
            warnings.add(WARNING_DOB_MISMATCH);
        }

        return List.copyOf(warnings);
    }
}
