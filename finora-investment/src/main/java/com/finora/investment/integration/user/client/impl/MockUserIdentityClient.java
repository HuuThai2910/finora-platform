package com.finora.investment.integration.user.client.impl;

import com.finora.investment.integration.user.client.UserIdentityClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Bản giả lập finora-user cho môi trường chạy Investment một mình: không có họ tên, không có CCCD (bật khi
 * {@code finora.investment.user.mode=stub}).
 */
@Component
@ConditionalOnProperty(prefix = "finora.investment.user", name = "mode", havingValue = "stub")
public class MockUserIdentityClient implements UserIdentityClient {

    @Override
    public UserIdentity identity(String userId) {
        return new UserIdentity(userId, null, null);
    }

    @Override
    public Map<String, String> fullNames(Collection<String> userIds) {
        return Map.of();
    }

    @Override
    public Set<String> userIdsByName(String query) {
        return Set.of();
    }
}
