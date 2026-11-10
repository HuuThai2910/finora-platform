package com.finora.investment.service.impl;

import com.finora.investment.integration.user.client.UserIdentityClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Họ tên người vay cho danh sách: đọc theo lô, nhớ 5 phút, User lỗi thì thiếu tên chứ không hỏng trang. */
class BorrowerNameLookupImplTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    private final UserIdentityClient userIdentityClient = mock(UserIdentityClient.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final BorrowerNameLookupImpl lookup = new BorrowerNameLookupImpl(userIdentityClient, clock);

    @Test
    void namesAreLoadedOnceAndServedFromCacheUntilTheyExpire() {
        Map<String, String> loaded = new HashMap<>();
        loaded.put("42", "Nguyễn Văn An");
        loaded.put("43", null);
        when(userIdentityClient.fullNames(List.of("42", "43"))).thenReturn(loaded);

        assertThat(lookup.fullNamesOf(List.of("42", "43", "42"))).containsExactly(Map.entry("42", "Nguyễn Văn An"));
        // Mở lại trong 5 phút, kể cả người chưa có họ tên, không gọi lại User.
        assertThat(lookup.fullNamesOf(List.of("43", "42"))).containsOnlyKeys("42");
        verify(userIdentityClient, times(1)).fullNames(anyCollection());
    }

    @Test
    void expiredEntriesAreReloaded() {
        when(userIdentityClient.fullNames(List.of("42"))).thenReturn(Map.of("42", "Nguyễn Văn An"));

        lookup.fullNamesOf(List.of("42"));
        clock.now = NOW.plus(BorrowerNameLookupImpl.TTL).plusSeconds(1);
        lookup.fullNamesOf(List.of("42"));

        verify(userIdentityClient, times(2)).fullNames(List.of("42"));
    }

    @Test
    void userFailureLeavesNamesOutAndIsRetriedNextTime() {
        when(userIdentityClient.fullNames(List.of("42")))
                .thenThrow(new UserIdentityClient.UserIdentityUnavailableException(
                        "USER_IDENTITY_UNAVAILABLE", "Không kết nối được User Service", null))
                .thenReturn(Map.of("42", "Nguyễn Văn An"));

        assertThat(lookup.fullNamesOf(List.of("42"))).isEmpty();
        assertThat(lookup.fullNamesOf(List.of("42"))).containsEntry("42", "Nguyễn Văn An");
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
