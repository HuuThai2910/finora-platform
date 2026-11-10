package com.finora.investment.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.investment.integration.user.client.UserIdentityClient;
import com.finora.investment.service.PartyNameSearch;
import com.finora.investment.util.SearchPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Gọi {@code GET /internal/users/ids-by-name} của User. Không đệm: chuỗi tìm đổi theo từng lần gõ và kết quả phải mới
 * (người vừa đổi tên vẫn tìm được).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyNameSearchImpl implements PartyNameSearch {

    private final UserIdentityClient userIdentityClient;

    @Override
    public Set<String> idsMatching(String query) {
        String value = SearchPatterns.trimmed(query);
        if (!PartyNameSearch.searchable(value)) {
            return Set.of();
        }
        try {
            return userIdentityClient.userIdsByName(value);
        } catch (UserIdentityClient.UserIdentityUnavailableException exception) {
            // Không ghi chuỗi tìm (là họ tên) ra log.
            log.warn("Không tra được người dùng theo họ tên: code={}", exception.code());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SEARCH_UNAVAILABLE",
                    "Chưa tìm được theo họ tên lúc này, vui lòng thử lại sau");
        }
    }
}
