package com.finora.notification.service.recipient;

import com.finora.notification.domain.entity.LoanReference;
import com.finora.notification.exception.RecipientConflictException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bảng tra người nhận theo khoản vay (D60, B64). Ghi trong transaction của intake: cùng commit với thông báo dùng nó.
 * Event mang người vay khác với dữ liệu đã có thì ném {@link RecipientConflictException} (vào DLT): gửi nhầm người là lộ
 * thông tin khoản vay, nên không bao giờ tự chọn một bên.
 */
public interface LoanRecipientService {

    /** Ghi người vay và mã công khai từ event; trả dòng tham chiếu sau khi cập nhật. */
    LoanReference remember(Long loanApplicationId, String borrowerId, String applicationNumber,
            String contractNumber, String loanNumber, UUID sourceEventId);

    Optional<LoanReference> find(Long loanApplicationId);

    /**
     * Người vay để gửi: ưu tiên {@code borrowerId} trong event (khi producer đã bổ sung, P1–P4), kiểm khớp bảng tra;
     * không có thì lấy từ bảng tra.
     */
    Optional<String> borrower(Long loanApplicationId, String borrowerFromEvent, String applicationNumber,
            String contractNumber, String loanNumber, UUID sourceEventId);

    /**
     * Thay danh sách nhà đầu tư theo allocation mới (đợt gọi vốn mới sau khi hủy). Allocation cũ hơn hoặc trùng thì bỏ
     * qua, nên event đến muộn/trùng không đổi danh sách hiện hành.
     */
    void replaceLenders(Long loanApplicationId, long allocationVersion, Collection<String> investorIds,
            String applicationNumber, UUID sourceEventId);

    /** Nhà đầu tư của allocation đang lưu; rỗng khi chưa nhận {@code LoanFullyFunded}. */
    Lenders lenders(Long loanApplicationId);

    /** Danh sách nhà đầu tư và allocation version tương ứng. */
    record Lenders(Long allocationVersion, List<String> investorIds) {
        public boolean known() {
            return allocationVersion != null && !investorIds.isEmpty();
        }

        /** Event mang allocation version: chỉ dùng danh sách khi đúng version đó. */
        public boolean matches(Long expectedVersion) {
            return known() && (expectedVersion == null || expectedVersion.equals(allocationVersion));
        }

        /** Event thuộc allocation cũ hơn danh sách đang lưu (đợt gọi vốn trước đã bị hủy): không còn ai cần báo. */
        public boolean supersedes(Long expectedVersion) {
            return known() && expectedVersion != null && allocationVersion > expectedVersion;
        }
    }
}
