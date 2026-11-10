package com.finora.investment.service.impl;

import com.finora.investment.integration.user.client.UserIdentityClient;
import com.finora.investment.service.BorrowerNameLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bộ nhớ đệm họ tên người vay theo từng instance.
 *
 * <p>App nhà đầu tư mở lại danh sách nhiều lần trong vài phút; họ tên gần như không đổi nên giữ {@link #TTL} để những lần
 * đó không gọi lại User. Người chưa có trong đệm được đọc một lần cho cả trang qua {@code GET /internal/users/full-names}.
 * Người User trả về không có họ tên cũng được nhớ (giá trị null) để không hỏi lại mỗi trang. Lỗi User không được nhớ: lần
 * mở sau thử lại.</p>
 *
 * <p>Giới hạn {@link #MAX_ENTRIES} chỉ để bộ nhớ không phình vô hạn; quá ngưỡng thì xóa hết, quy mô đồ án vài trăm
 * người vay nên hầu như không xảy ra.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BorrowerNameLookupImpl implements BorrowerNameLookup {

    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_ENTRIES = 10_000;

    private final UserIdentityClient userIdentityClient;
    private final Clock clock;
    private final Map<String, CachedName> cache = new ConcurrentHashMap<>();

    @Override
    public Map<String, String> fullNamesOf(Collection<String> borrowerIds) {
        Instant now = clock.instant();
        Map<String, String> names = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String borrowerId : borrowerIds.stream().filter(Objects::nonNull).distinct().toList()) {
            if (borrowerId.isBlank()) {
                continue;
            }
            CachedName cached = cache.get(borrowerId);
            if (cached != null && cached.expiresAt().isAfter(now)) {
                if (cached.fullName() != null) {
                    names.put(borrowerId, cached.fullName());
                }
            } else {
                missing.add(borrowerId);
            }
        }
        if (missing.isEmpty()) {
            return names;
        }
        Map<String, String> loaded;
        try {
            loaded = userIdentityClient.fullNames(missing);
        } catch (UserIdentityClient.UserIdentityUnavailableException exception) {
            log.warn("Không đọc được họ tên người vay theo lô: borrowers={}, code={}", missing.size(), exception.code());
            return names;
        }
        if (cache.size() + missing.size() > MAX_ENTRIES) {
            cache.clear();
        }
        Instant expiresAt = now.plus(TTL);
        for (String borrowerId : missing) {
            String fullName = loaded.get(borrowerId);
            cache.put(borrowerId, new CachedName(fullName, expiresAt));
            if (fullName != null) {
                names.put(borrowerId, fullName);
            }
        }
        return names;
    }

    private record CachedName(String fullName, Instant expiresAt) {
    }
}
