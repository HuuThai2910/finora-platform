package com.finora.loan.service.application.impl;

import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.service.application.ApplicationNumberLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ApplicationNumberLookupServiceImpl implements ApplicationNumberLookupService {

    private final LoanApplicationRepository loanApplicationRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> numbersOf(Collection<Long> applicationIds) {
        List<Long> ids = applicationIds == null ? List.of()
                : applicationIds.stream().filter(Objects::nonNull).distinct().limit(MAX_IDS).toList();
        Map<Long, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (Object[] row : loanApplicationRepository.findApplicationNumbers(ids)) {
            result.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return result;
    }
}
