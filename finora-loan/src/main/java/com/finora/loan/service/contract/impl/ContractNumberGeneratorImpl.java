package com.finora.loan.service.contract.impl;

import com.finora.loan.service.contract.ContractNumberGenerator;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Sinh mã hợp đồng vay dạng {@code LC-} và 20 ký tự hex viết hoa.
 *
 * <p>Lấy 20 ký tự đầu của một UUID ngẫu nhiên đã bỏ dấu gạch.</p>
 */
@Component
public class ContractNumberGeneratorImpl implements ContractNumberGenerator {

    @Override
    public String next() {
        return "LC-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20)
                .toUpperCase(Locale.ROOT);
    }
}
