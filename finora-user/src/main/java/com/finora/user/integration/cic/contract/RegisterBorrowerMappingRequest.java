package com.finora.user.integration.cic.contract;

/** CCCD chỉ đi qua HTTP nội bộ có API key; tuyệt đối không publish lên Kafka. */
public record RegisterBorrowerMappingRequest(String borrowerId, String soCccd) {
}
