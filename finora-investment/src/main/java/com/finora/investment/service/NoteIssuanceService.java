package com.finora.investment.service;

import com.finora.investment.dto.response.NoteResponse;
import java.util.List;

/**
 * Khóa vốn trước giải ngân và xé nhỏ phần vốn thành Notes.
 *
 * <p>Tách interface khỏi cài đặt theo đúng quy ước của finora-user và finora-loan:
 * controller phụ thuộc vào hợp đồng này, không phụ thuộc vào lớp cài đặt.</p>
 */
public interface NoteIssuanceService {

    /**
     * Khóa toàn bộ phần vốn của một khoản vay trước khi Payment bắt tiền thật (F05 bước 2).
     *
     * <p>Sau bước này nhà đầu tư không hủy lệnh được nữa. Chạy lại trên khoản vay đã khóa
     * không phải lỗi — Saga có thể lặp lại bước này sau khi khởi động lại.</p>
     *
     * @return số phần vốn vừa được khóa trong lần gọi này
     */
    int finalizeCommitments(Long listingId);

    /**
     * Phát hành Note cho toàn bộ phần vốn đã khóa của một khoản vay.
     *
     * <p>Idempotent theo commitment: commitment nào đã có Note thì bỏ qua, nên Saga gọi lại
     * sau khi lỗi giữa chừng sẽ chỉ phát hành phần còn thiếu, không nhân đôi quyền sở hữu
     * (F05 failure — "Note activation lỗi sau disbursement → Saga retry/repair").</p>
     *
     * @return tổng số Note vừa phát hành trong lần gọi này
     */
    int activateNotes(Long listingId);

    /**
     * Thao tác sửa lỗi của quản trị: chỉ khóa vốn/phát hành Note khi Loan đã xác nhận giải ngân (listing có
     * {@code disbursedAt}). Trước giải ngân, tiền nhà đầu tư mới được giữ; khóa vốn sẽ chặn bù trừ khi hợp đồng bị
     * từ chối và phát hành Note sẽ cho nhà đầu tư quyền với khoản vay chưa nhận tiền.
     */
    int finalizeCommitmentsAfterDisbursement(Long listingId);

    /** @see #finalizeCommitmentsAfterDisbursement(Long) */
    int activateNotesAfterDisbursement(Long listingId);

    /** Danh sách Note của một phần vốn, dùng cho màn hình chi tiết khoản đầu tư. */
    List<NoteResponse> notesOfCommitment(Long commitmentId);
}
