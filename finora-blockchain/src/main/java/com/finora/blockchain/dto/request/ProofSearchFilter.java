package com.finora.blockchain.dto.request;

import com.finora.blockchain.domain.proof.enums.ProofProviderType;
import com.finora.blockchain.domain.proof.enums.ProofType;
import com.finora.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

/**
 * Bộ lọc thêm của explorer proof (bảng lọc trang Kiểm toán): loại bằng chứng, sổ cái và khoảng ngày tạo (giờ Việt Nam,
 * gồm cả hai đầu). Mọi trường tùy chọn; {@code null} là không lọc.
 */
public record ProofSearchFilter(ProofType proofType, ProofProviderType provider, LocalDate createdFrom,
                                LocalDate createdTo) {

    public static final ProofSearchFilter NONE = new ProofSearchFilter(null, null, null, null);

    public boolean isEmpty() {
        return proofType == null && provider == null && createdFrom == null && createdTo == null;
    }

    /** "Từ ngày" sau "đến ngày" trả 400 {@code FILTER_RANGE_INVALID}. */
    public ProofSearchFilter validate() {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "FILTER_RANGE_INVALID",
                    "Thời gian: ngày \"từ\" phải trước hoặc bằng ngày \"đến\"");
        }
        return this;
    }
}
