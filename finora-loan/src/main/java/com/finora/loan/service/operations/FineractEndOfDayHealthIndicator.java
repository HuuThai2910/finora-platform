package com.finora.loan.service.operations;

import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health {@code fineractEndOfDay} (W3E, D57): báo độ tươi của job cuối ngày Fineract trong nhóm {@code operations} và
 * health tổng, không nằm trong liveness/readiness.
 */
public interface FineractEndOfDayHealthIndicator extends HealthIndicator {
}
