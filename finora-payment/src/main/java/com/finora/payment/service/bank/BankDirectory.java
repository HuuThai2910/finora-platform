package com.finora.payment.service.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Danh bạ ngân hàng nhận chuyển khoản NAPAS tại Việt Nam (mã, BIN, tên, logo), đọc từ {@code banks/vn-banks.json}.
 * <p>
 * File là bản chụp danh sách công khai của VietQR ({@code https://api.vietqr.io/v2/banks}), chỉ giữ ngân hàng hỗ trợ
 * chuyển khoản và bỏ ví điện tử. Đọc từ file đóng gói thay vì gọi VietQR lúc chạy: danh bạ đổi rất ít, còn Payment
 * không được phụ thuộc một dịch vụ bên ngoài chỉ để hiện danh sách ngân hàng. Cập nhật bằng cách tải lại file.
 */
@Component
public class BankDirectory {

    static final String RESOURCE = "banks/vn-banks.json";

    /**
     * Mã cũ của danh sách 10 ngân hàng trước đây, vẫn nằm trong lệnh rút đã lưu: đọc ra theo mã VietQR tương ứng.
     */
    private static final Map<String, String> LEGACY_CODES = Map.of("CTG", "ICB", "AGR", "VBA");

    private final List<BankInfo> banks;
    private final Map<String, BankInfo> byCode;

    public BankDirectory(ObjectMapper objectMapper) {
        this(load(objectMapper));
    }

    BankDirectory(List<BankInfo> banks) {
        if (banks.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " không có ngân hàng nào");
        }
        Map<String, BankInfo> index = new LinkedHashMap<>();
        for (BankInfo bank : banks) {
            if (index.put(bank.code(), bank) != null) {
                throw new IllegalStateException(RESOURCE + " trùng mã ngân hàng " + bank.code());
            }
        }
        this.banks = List.copyOf(banks);
        this.byCode = Map.copyOf(index);
    }

    /** Toàn bộ ngân hàng theo thứ tự của danh bạ (ngân hàng lớn trước). */
    public List<BankInfo> all() {
        return banks;
    }

    public Optional<BankInfo> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Optional.ofNullable(byCode.get(LEGACY_CODES.getOrDefault(normalized, normalized)));
    }

    /** Tên ngắn để hiển thị; mã lạ (ngân hàng đã rời danh bạ) thì trả lại chính mã đó. */
    public String displayName(String code) {
        return find(code).map(BankInfo::shortName).orElse(code);
    }

    private static List<BankInfo> load(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(input, Snapshot.class).banks();
        } catch (IOException exception) {
            throw new IllegalStateException("Không đọc được danh bạ ngân hàng " + RESOURCE, exception);
        }
    }

    private record Snapshot(String source, String fetchedAt, List<BankInfo> banks) {
    }
}
