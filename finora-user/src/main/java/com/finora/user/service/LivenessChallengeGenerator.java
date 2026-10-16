package com.finora.user.service;

import com.finora.user.domain.LivenessAction;
import com.finora.user.domain.LivenessStep;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Sinh lịch thử thách liveness ngẫu nhiên.
 * <p>
 * Chọn 3 hành động khác nhau trong 4 (24 thứ tự) bằng {@link SecureRandom}, mỗi hành động giữ 900–1700 ms
 * (bước 100 ms), FRONTAL đầu cố định 1500 ms để AI lấy mức nền tư thế, và chèn FRONTAL giữa các hành
 * động lẫn ở cuối. Kẻ gian không biết trước thứ tự lẫn nhịp nên video quay sẵn không khớp được: AI
 * kiểm cả chuỗi hành động (đúng, không thừa) và khoảng cách giữa các đỉnh.
 */
@Component
public class LivenessChallengeGenerator {

    static final int FIRST_FRONTAL_HOLD_MS = 1500;
    static final int MIN_HOLD_MS = 900;
    static final int MAX_HOLD_MS = 1700;
    static final int HOLD_STEP_MS = 100;
    static final int ACTIONS_PER_CHALLENGE = 3;
    /** Dư cho người dùng bấm quay và kết thúc chậm một chút. */
    static final int DURATION_MARGIN_SEC = 3;

    private final Random random;

    public LivenessChallengeGenerator() {
        this(new SecureRandom());
    }

    /** Bơm nguồn ngẫu nhiên cố định trong test để kết quả lặp lại được. */
    LivenessChallengeGenerator(Random random) {
        this.random = random;
    }

    public List<LivenessStep> generate() {
        List<LivenessAction> pool = new ArrayList<>(LivenessAction.CHALLENGE_POOL);
        Collections.shuffle(pool, random);
        List<LivenessStep> steps = new ArrayList<>();
        steps.add(new LivenessStep(LivenessAction.FRONTAL, FIRST_FRONTAL_HOLD_MS));
        for (LivenessAction action : pool.subList(0, ACTIONS_PER_CHALLENGE)) {
            steps.add(new LivenessStep(action, randomHoldMs()));
            steps.add(new LivenessStep(LivenessAction.FRONTAL, randomHoldMs()));
        }
        return List.copyOf(steps);
    }

    /** Thời lượng quay tối đa = làm tròn lên tổng thời gian giữ (giây) + 3 giây. */
    public static int maxDurationSec(List<LivenessStep> steps) {
        long totalMs = steps.stream().mapToLong(LivenessStep::holdMs).sum();
        return (int) Math.ceil(totalMs / 1000.0) + DURATION_MARGIN_SEC;
    }

    private int randomHoldMs() {
        int choices = (MAX_HOLD_MS - MIN_HOLD_MS) / HOLD_STEP_MS + 1;
        return MIN_HOLD_MS + HOLD_STEP_MS * random.nextInt(choices);
    }
}
