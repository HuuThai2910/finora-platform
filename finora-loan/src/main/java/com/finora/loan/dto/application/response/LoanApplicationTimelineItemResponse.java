package com.finora.loan.dto.application.response;

import com.finora.loan.domain.application.enums.ActorType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một mốc trong lịch sử xử lý đầy đủ của hồ sơ, từ lúc nộp tới khi hợp đồng đóng.
 *
 * <p>Ghép từ các nguồn chuẩn sẵn có của Loan (history hồ sơ, history hợp đồng, các bên ký, giải ngân, cơ cấu nợ, thu hồi
 * nợ, khoản vay). Không chứa văn bản hiển thị: client dịch {@code eventCode}, trạng thái và {@code reasonCode} sang nhãn.
 * Sự kiện chỉ nằm ở Investment (lệnh góp vốn từng người) không có ở đây vì Loan không đọc dữ liệu service khác.</p>
 *
 * @param key        khóa ổn định để client render danh sách (nguồn + id)
 * @param stage      nhóm: {@code APPLICATION}, {@code FUNDING}, {@code CONTRACT}, {@code DISBURSEMENT},
 *                   {@code SERVICING}, {@code RESTRUCTURE}, {@code COLLECTION}
 * @param eventCode  loại mốc, ví dụ {@code APPLICATION_STATUS}, {@code CONTRACT_STATUS}, {@code LENDER_SIGNED}
 * @param fromStatus trạng thái trước (nếu mốc là chuyển trạng thái)
 * @param toStatus   trạng thái sau, hoặc loại yêu cầu/hành động với mốc không phải chuyển trạng thái
 * @param amount     số tiền gắn với mốc (phần vốn, số tiền giải ngân...) nếu có
 */
public record LoanApplicationTimelineItemResponse(
        String key,
        Instant occurredAt,
        String stage,
        String eventCode,
        String fromStatus,
        String toStatus,
        String reasonCode,
        String reasonDetail,
        ActorType actorType,
        String actorId,
        BigDecimal amount
) {
}
