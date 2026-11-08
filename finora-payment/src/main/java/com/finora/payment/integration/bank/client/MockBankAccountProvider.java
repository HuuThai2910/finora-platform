package com.finora.payment.integration.bank.client;

import com.finora.payment.util.VietnameseNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Ngân hàng giả lập (BL-001) — không gọi ngân hàng nào. Kết quả tất định theo <b>4 số cuối</b> của số tài khoản, cùng
 * quy ước với provider chi tiền giả lập ({@code 0000} là tài khoản sai), để demo đủ mọi nhánh:
 * <ul>
 *   <li>{@code 0000}: không tồn tại tài khoản;</li>
 *   <li>{@code 9999}: tài khoản bị khóa;</li>
 *   <li>{@code 5555}: ngân hàng không phản hồi (thử lại sau);</li>
 *   <li>{@code 1111}: tài khoản của người khác — tên chủ tài khoản sinh từ số tài khoản, không khớp eKYC;</li>
 *   <li>còn lại: tài khoản của chính người đang liên kết — tên chủ tài khoản là tên eKYC dạng ngân hàng in.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "finora.payment.bank-link.provider", havingValue = "mock", matchIfMissing = true)
public class MockBankAccountProvider implements BankAccountProvider {

    static final String NOT_FOUND_SUFFIX = "0000";
    static final String LOCKED_SUFFIX = "9999";
    static final String UNAVAILABLE_SUFFIX = "5555";
    static final String OTHER_OWNER_SUFFIX = "1111";

    private static final List<String> FAMILY = List.of("NGUYEN", "TRAN", "LE", "PHAM", "HOANG", "VU", "DANG", "BUI");
    private static final List<String> MIDDLE = List.of("VAN", "THI", "MINH", "DUC", "NGOC", "THANH", "QUOC", "HUU");
    private static final List<String> GIVEN = List.of("AN", "BINH", "CHI", "DUNG", "HA", "KHANH", "LAN", "TUAN");

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public Inquiry inquire(String bankCode, String accountNumber, String ownerNameHint) {
        if (accountNumber.endsWith(UNAVAILABLE_SUFFIX)) {
            throw new BankUnavailableException("Ngân hàng giả lập không phản hồi (đuôi " + UNAVAILABLE_SUFFIX + ")");
        }
        if (accountNumber.endsWith(NOT_FOUND_SUFFIX)) {
            return Inquiry.notFound();
        }
        if (accountNumber.endsWith(LOCKED_SUFFIX)) {
            return Inquiry.locked();
        }
        if (accountNumber.endsWith(OTHER_OWNER_SUFFIX) || ownerNameHint == null || ownerNameHint.isBlank()) {
            return Inquiry.found(strangerName(bankCode, accountNumber));
        }
        return Inquiry.found(VietnameseNames.bankStyle(ownerNameHint));
    }

    /** Cùng ngân hàng + số tài khoản luôn ra cùng một tên, như tra cứu thật. */
    static String strangerName(String bankCode, String accountNumber) {
        CRC32 crc = new CRC32();
        crc.update((bankCode + "|" + accountNumber).getBytes(StandardCharsets.UTF_8));
        long seed = crc.getValue();
        return FAMILY.get((int) (seed % FAMILY.size())) + " "
                + MIDDLE.get((int) ((seed / 8) % MIDDLE.size())) + " "
                + GIVEN.get((int) ((seed / 64) % GIVEN.size()));
    }
}
