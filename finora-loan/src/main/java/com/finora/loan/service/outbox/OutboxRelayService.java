package com.finora.loan.service.outbox;

/**
 * Relay một outbox event ra transport: gọi broker ngoài transaction rồi ghi kết quả ở transaction kế tiếp.
 */
public interface OutboxRelayService {

    /** Claim event {@code id}, gửi qua transport rồi ghi kết quả; không claim được thì bỏ qua. */
    void publish(Long id);
}
