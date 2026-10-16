package com.finora.loan.service.contract;

/**
 * Điều khoản servicing được đóng băng trong hợp đồng mới.
 *
 * <p>Loan chỉ công bố điều khoản; Payment vẫn là nguồn chuẩn tính quote và phân phối tiền. Nội dung ở đây
 * phải đổi phiên bản cùng policy Payment để người ký không nhìn thấy một cách tính khác cách hệ thống thu.</p>
 */
final class LoanServicingContractTerms {

    static final String POLICY_VERSION = "FINORA_SERVICING_TERMS_V2";
    static final String EARLY_SETTLEMENT_POLICY_VERSION = "AGRIBANK_REFERENCE_FINORA_2026_V2";

    private LoanServicingContractTerms() {
    }

    static String plainText(int termMonths) {
        return """
                4. CHÍNH SÁCH TRẢ NỢ, TRẢ TRƯỚC VÀ CƠ CẤU
                Trả nợ đúng hạn: người vay thanh toán gốc, lãi, phí và tiền phạt của kỳ theo lịch chính thức tại Apache Fineract.
                Quá hạn: lãi trên gốc quá hạn không vượt 150%% lãi suất trong hạn tương ứng; lãi chậm trả trên phần lãi chưa trả không vượt 10%%/năm. Số tiền thực tế lấy từ lịch và breakdown Fineract đã công bố.
                Trả trước một phần: số tiền thu gồm nghĩa vụ đã đến hạn, phần gốc trả thêm và phí trả trước. Không thu lãi của các tháng tương lai trên phần gốc đã trả; lịch còn lại do Fineract tính lại, Product V3 ưu tiên rút ngắn số kỳ.
                Tất toán trước hạn: số tiền thu gồm toàn bộ gốc còn lại, lãi/phí/phạt đã phát sinh đến ngày giao dịch và phí trả trước; không thu lãi của các tháng tương lai.
                %s
                Khi tỷ lệ phí lớn hơn 0, phí tối thiểu là 100.000 đồng. Phí tính trên phần gốc trả thêm khi trả trước một phần, hoặc toàn bộ gốc còn nợ khi tất toán. 80%% tổng phí được chia cho các nhà đầu tư theo dư nợ Note; 20%% thuộc FINORA.
                Điều chỉnh kỳ hạn/gia hạn hiện không thu phí yêu cầu. Lịch cũ tiếp tục có hiệu lực cho đến khi FINORA thẩm định đạt, toàn bộ nhà đầu tư đang nắm Note đồng ý và Fineract áp dụng lịch mới thành công.
                Phiên bản điều khoản servicing: %s. Phiên bản biểu phí trả trước: %s.
                """.formatted(feeBandText(termMonths), POLICY_VERSION,
                EARLY_SETTLEMENT_POLICY_VERSION).stripTrailing();
    }

    static String html(int termMonths) {
        return """
                <h2>ĐIỀU 4. TRẢ NỢ, TRẢ TRƯỚC VÀ CƠ CẤU</h2>
                <ol>
                  <li>Trả nợ đúng hạn gồm gốc, lãi, phí và tiền phạt của kỳ theo lịch chính thức tại Apache Fineract.</li>
                  <li>Khi quá hạn, lãi trên gốc quá hạn không vượt 150%% lãi suất trong hạn tương ứng; lãi chậm trả trên phần lãi chưa trả không vượt 10%%/năm. Số tiền thực tế lấy từ lịch và breakdown Fineract đã công bố.</li>
                  <li>Trả trước một phần gồm nghĩa vụ đã đến hạn, phần gốc trả thêm và phí trả trước. Không thu lãi của các tháng tương lai trên phần gốc đã trả; lịch còn lại do Fineract tính lại, Product V3 ưu tiên rút ngắn số kỳ.</li>
                  <li>Tất toán trước hạn gồm toàn bộ gốc còn lại, lãi/phí/phạt đã phát sinh đến ngày giao dịch và phí trả trước; không thu lãi của các tháng tương lai.</li>
                  <li>%s</li>
                  <li>Khi tỷ lệ phí lớn hơn 0, phí tối thiểu là <b>100.000 đồng</b>. Phí tính trên phần gốc trả thêm khi trả trước một phần, hoặc toàn bộ gốc còn nợ khi tất toán. <b>80%%</b> tổng phí được chia cho các nhà đầu tư theo dư nợ Note; <b>20%%</b> thuộc FINORA.</li>
                  <li>Điều chỉnh kỳ hạn/gia hạn hiện không thu phí yêu cầu. Lịch cũ tiếp tục có hiệu lực cho đến khi FINORA thẩm định đạt, toàn bộ nhà đầu tư đang nắm Note đồng ý và Fineract áp dụng lịch mới thành công.</li>
                </ol>
                <div class="notice blue"><b>PHIÊN BẢN CHÍNH SÁCH</b><br/>Điều khoản servicing: %s<br/>Biểu phí trả trước: %s</div>
                """.formatted(escape(feeBandText(termMonths)), POLICY_VERSION,
                EARLY_SETTLEMENT_POLICY_VERSION);
    }

    private static String feeBandText(int termMonths) {
        if (termMonths <= 12) {
            return "Khoản vay từ 12 tháng trở xuống: trả trong 50% thời hạn đầu áp dụng 1%; sau 50% đến hết 70% thời hạn áp dụng 0,5%; sau 70% thời hạn áp dụng 0%.";
        }
        return "Khoản vay trên 12 đến 24 tháng: trả trong 12 tháng đầu áp dụng 2%; từ tháng thứ 13 áp dụng 1,5%.";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
