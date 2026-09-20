package com.finora.common.enums.investment;

/**
 * Trạng thái một chứng chỉ đầu tư (Note).
 *
 * <p>ACTIVE khi đã phát hành và còn dư nợ gốc; CLOSED khi thu hồi hết gốc;
 * DEFAULTED khi khoản vay chuyển nợ xấu và ngừng sinh dòng tiền kỳ vọng.</p>
 */
public enum NoteStatus {
    ACTIVE,
    CLOSED,
    DEFAULTED
}
