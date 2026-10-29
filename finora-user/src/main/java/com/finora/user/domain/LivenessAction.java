package com.finora.user.domain;

import java.util.List;

/**
 * Tư thế người dùng phải giữ trong video liveness.
 * <p>
 * Chỉ dùng hành động hình học (quay đầu, ngẩng, cúi): chúng đo được bằng 5 điểm mốc YuNet và bền với
 * ảnh mờ hơn nháy mắt. Tên enum là giá trị trao đổi với mobile và finora-ai — không đổi tên.
 */
public enum LivenessAction {
    /** Nhìn thẳng — mốc nền giữa hai hành động. */
    FRONTAL,
    TURN_LEFT,
    TURN_RIGHT,
    LOOK_UP,
    LOOK_DOWN;

    /** Bốn hành động server được chọn ngẫu nhiên (FRONTAL luôn được chèn xen kẽ). */
    public static final List<LivenessAction> CHALLENGE_POOL = List.of(TURN_LEFT, TURN_RIGHT, LOOK_UP, LOOK_DOWN);
}
