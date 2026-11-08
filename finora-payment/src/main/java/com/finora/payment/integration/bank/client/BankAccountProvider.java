package com.finora.payment.integration.bank.client;

/**
 * Ngân hàng (hoặc cổng NAPAS) mà Payment hỏi khi liên kết tài khoản (BL-001): tra cứu chủ tài khoản theo số tài khoản.
 * Provider thật (Napas 247 / API ngân hàng) chỉ cần cài interface này; luồng liên kết, OTP và rút tiền không đổi.
 */
public interface BankAccountProvider {

    /** Tên provider lưu vào log và cảnh báo cấu hình giả lập. */
    String name();

    /**
     * Tra cứu chủ tài khoản. Lỗi kết nối hoặc ngân hàng không phản hồi ném {@link BankUnavailableException}; còn lại
     * trả kết quả nghiệp vụ.
     *
     * @param ownerNameHint họ tên eKYC của người đang liên kết. Ngân hàng thật bỏ qua tham số này; provider giả lập dùng
     *                      để "sở hữu" tài khoản của chính người dùng, vì không có sổ khách hàng nào để tra.
     */
    Inquiry inquire(String bankCode, String accountNumber, String ownerNameHint);

    enum Outcome {
        /** Tài khoản tồn tại và nhận được tiền; {@code holderName} có giá trị. */
        FOUND,
        /** Không có tài khoản với số này ở ngân hàng đã chọn. */
        NOT_FOUND,
        /** Tài khoản bị khóa/phong tỏa, không nhận tiền được. */
        LOCKED
    }

    /**
     * @param holderName tên chủ tài khoản do ngân hàng trả, IN HOA không dấu như trên hệ thống ngân hàng
     */
    record Inquiry(Outcome outcome, String holderName) {
        public static Inquiry found(String holderName) {
            return new Inquiry(Outcome.FOUND, holderName);
        }

        public static Inquiry notFound() {
            return new Inquiry(Outcome.NOT_FOUND, null);
        }

        public static Inquiry locked() {
            return new Inquiry(Outcome.LOCKED, null);
        }
    }

    /** Ngân hàng không phản hồi hoặc trả lỗi hệ thống: thử lại sau, không phải lỗi của người dùng. */
    class BankUnavailableException extends RuntimeException {
        public BankUnavailableException(String message) {
            super(message);
        }
    }
}
