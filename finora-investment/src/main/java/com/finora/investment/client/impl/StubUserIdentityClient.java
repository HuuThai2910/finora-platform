package com.finora.investment.client.impl;

import com.finora.investment.client.UserIdentityClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Bản giả lập finora-user cho môi trường chạy Investment một mình: không có họ tên, không có CCCD. */
@Component
@ConditionalOnProperty(prefix = "finora.investment.user", name = "mode", havingValue = "stub")
public class StubUserIdentityClient implements UserIdentityClient {

    @Override
    public UserIdentity identity(String userId) {
        return new UserIdentity(userId, null, null);
    }
}
