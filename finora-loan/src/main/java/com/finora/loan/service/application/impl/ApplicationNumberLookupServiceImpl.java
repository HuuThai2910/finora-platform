package com.finora.loan.service.application.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.dto.decision.response.ApplicationNumberResponse;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.service.application.ApplicationNumberLookupService;
import com.finora.loan.util.DisplayCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ApplicationNumberLookupServiceImpl implements ApplicationNumberLookupService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanContractRepository loanContractRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> numbersOf(Collection<Long> applicationIds) {
        List<Long> ids = limitedIds(applicationIds);
        Map<Long, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (Object[] row : loanApplicationRepository.findApplicationNumbers(ids)) {
            result.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApplicationNumberResponse> referencesOf(Collection<Long> applicationIds) {
        List<Long> ids = limitedIds(applicationIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return loanApplicationRepository.findApplicationReferences(ids).stream()
                .map(row -> new ApplicationNumberResponse(
                        ((Number) row[0]).longValue(), (String) row[1], (String) row[2]))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationNumberResponse resolve(String code) {
        String value = DisplayCodes.normalize(code);
        Optional<LoanApplication> application = Optional.empty();
        if (value != null) {
            if (value.startsWith(DisplayCodes.APPLICATION_PREFIX + "-")) {
                application = loanApplicationRepository.findByApplicationCode(value);
            } else if (value.startsWith(DisplayCodes.CONTRACT_PREFIX + "-")) {
                application = loanContractRepository.findApplicationIdByContractCode(value)
                        .flatMap(loanApplicationRepository::findById);
            } else if (value.startsWith("LC-")) {
                application = loanContractRepository.findApplicationIdByContractNumber(value)
                        .flatMap(loanApplicationRepository::findById);
            } else {
                application = loanApplicationRepository.findByApplicationNumber(value);
            }
        }
        LoanApplication found = application
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ vay", "code", code));
        // Trang Xác minh dữ liệu tra bằng chứng hợp đồng theo mã LC-: trả kèm cặp mã hợp đồng nếu đã có.
        LoanContractRepository.ContractCodeView contract = loanContractRepository
                .findContractReferenceByApplicationId(found.getId()).orElse(null);
        return new ApplicationNumberResponse(found.getId(), found.getApplicationNumber(), found.getApplicationCode(),
                contract == null ? null : contract.getContractNumber(),
                contract == null ? null : contract.getContractCode());
    }

    private static List<Long> limitedIds(Collection<Long> applicationIds) {
        return applicationIds == null ? List.of()
                : applicationIds.stream().filter(Objects::nonNull).distinct().limit(MAX_IDS).toList();
    }
}
