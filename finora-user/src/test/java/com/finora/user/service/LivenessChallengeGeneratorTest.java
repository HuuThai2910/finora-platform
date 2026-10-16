package com.finora.user.service;

import com.finora.user.domain.LivenessAction;
import com.finora.user.domain.LivenessStep;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Kiểm tra ràng buộc của lịch thử thách liveness (EKYC-002 mục 3.4). */
class LivenessChallengeGeneratorTest {

    private final LivenessChallengeGenerator generator = new LivenessChallengeGenerator();

    @RepeatedTest(200)
    void threeDistinctActionsInterleavedWithFrontal() {
        List<LivenessStep> steps = generator.generate();

        assertThat(steps).hasSize(7);
        assertThat(steps.get(0)).isEqualTo(new LivenessStep(LivenessAction.FRONTAL, 1500));
        // Vị trí lẻ là hành động, vị trí chẵn (kể cả cuối cùng) là FRONTAL.
        for (int i = 0; i < steps.size(); i++) {
            if (i % 2 == 0) {
                assertThat(steps.get(i).action()).isEqualTo(LivenessAction.FRONTAL);
            } else {
                assertThat(steps.get(i).action()).isIn(LivenessAction.CHALLENGE_POOL);
            }
        }
        Set<LivenessAction> actions = new HashSet<>();
        for (int i = 1; i < steps.size(); i += 2) {
            actions.add(steps.get(i).action());
        }
        assertThat(actions).as("ba hành động khác nhau").hasSize(3);
        assertThat(steps.subList(1, steps.size()))
                .allSatisfy(step -> assertThat(step.holdMs()).isBetween(900, 1700));
    }

    @Test
    void maxDurationIsCeilOfTotalHoldPlusThreeSeconds() {
        List<LivenessStep> steps = List.of(
                new LivenessStep(LivenessAction.FRONTAL, 1500), new LivenessStep(LivenessAction.TURN_LEFT, 1300),
                new LivenessStep(LivenessAction.FRONTAL, 1100), new LivenessStep(LivenessAction.LOOK_UP, 1600),
                new LivenessStep(LivenessAction.FRONTAL, 900), new LivenessStep(LivenessAction.TURN_RIGHT, 1400),
                new LivenessStep(LivenessAction.FRONTAL, 1000));

        // Tổng 8800 ms → làm tròn lên 9 giây + 3 giây dư.
        assertThat(LivenessChallengeGenerator.maxDurationSec(steps)).isEqualTo(12);
    }

    @Test
    void exactSecondsAreNotRoundedUp() {
        List<LivenessStep> steps = List.of(new LivenessStep(LivenessAction.FRONTAL, 1500),
                new LivenessStep(LivenessAction.TURN_LEFT, 1500));

        assertThat(LivenessChallengeGenerator.maxDurationSec(steps)).isEqualTo(6);
    }

    @Test
    void everyActionOrderCanAppear() {
        LivenessChallengeGenerator seeded = new LivenessChallengeGenerator(new Random(42));
        Set<List<LivenessAction>> orders = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            List<LivenessStep> steps = seeded.generate();
            orders.add(List.of(steps.get(1).action(), steps.get(3).action(), steps.get(5).action()));
        }
        // 4 hành động chọn 3 có thứ tự = 24 chuỗi; nguồn ngẫu nhiên phải phủ hết.
        assertThat(orders).hasSize(24);
    }
}
