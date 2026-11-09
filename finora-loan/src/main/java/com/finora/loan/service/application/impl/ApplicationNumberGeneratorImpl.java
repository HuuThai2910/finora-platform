package com.finora.loan.service.application.impl;

import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.service.application.ApplicationNumberGenerator;
import com.finora.loan.util.DisplayCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Sinh mã hồ sơ vay dạng {@code LA-} và 20 ký tự hex viết hoa, kèm mã hiển thị {@code HS-yyyyMMdd-XXXX}.
 *
 * <p>Mã tra cứu lấy 20 ký tự đầu của một UUID ngẫu nhiên đã bỏ dấu gạch. Mã hiển thị chỉ có khoảng 707 nghìn giá trị
 * mỗi ngày nên kiểm trùng trước và chọn lại vài lần; quá số lần thì dừng thay vì ghi mã trùng.</p>
 */
@Component
@RequiredArgsConstructor
public class ApplicationNumberGeneratorImpl implements ApplicationNumberGenerator {

    static final int MAX_CODE_ATTEMPTS = 5;

    private final LoanApplicationRepository loanApplicationRepository;

    @Override
    public String next() {
        return "LA-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20)
                .toUpperCase(Locale.ROOT);
    }

    @Override
    public String nextCode(Instant createdAt) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = DisplayCodes.application(createdAt);
            if (!loanApplicationRepository.existsByApplicationCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Không chọn được mã hiển thị hồ sơ chưa dùng sau "
                + MAX_CODE_ATTEMPTS + " lần thử");
    }
}
