package com.finora.loan.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingServiceTest {

    private final HashingService hashingService = new HashingService(new ObjectMapper());

    @Test
    void shouldCreateSameHashForMapsWithDifferentInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("amount", 50_000_000);
        first.put("term", 12);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("term", 12);
        second.put("amount", 50_000_000);

        assertThat(hashingService.sha256(first)).isEqualTo(hashingService.sha256(second));
    }
}
