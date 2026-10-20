package com.finora.loan.domain.contract;

/**
 * Cách hợp đồng được ký, chốt một lần khi lập Contract và không đổi giữa chừng.
 *
 * <p>{@code DETACHED_EVIDENCE}: luồng trước R5, PDF giữ nguyên bytes và bằng chứng ký nằm ngoài tệp (hash + receipt).
 * {@code PADES_B_B}: mỗi bên nhúng một chữ ký PAdES-B-B ẩn vào chính tệp PDF theo bản cập nhật gia tăng.</p>
 */
public enum ContractSignatureProfile {
    DETACHED_EVIDENCE,
    PADES_B_B
}
