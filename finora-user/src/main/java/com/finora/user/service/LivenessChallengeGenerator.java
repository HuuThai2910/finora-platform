package com.finora.user.service;

import com.finora.user.domain.LivenessStep;

import java.util.List;

/**
 * Sinh lịch thử thách liveness ngẫu nhiên.
 */
public interface LivenessChallengeGenerator {

    /** Dư cho người dùng bấm quay và kết thúc chậm một chút. */
    int DURATION_MARGIN_SEC = 3;

    List<LivenessStep> generate();

    /** Thời lượng quay tối đa = làm tròn lên tổng thời gian giữ (giây) + 3 giây. */
    static int maxDurationSec(List<LivenessStep> steps) {
        long totalMs = steps.stream().mapToLong(LivenessStep::holdMs).sum();
        return (int) Math.ceil(totalMs / 1000.0) + DURATION_MARGIN_SEC;
    }
}
