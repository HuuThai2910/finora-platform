package com.finora.user.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Hành động người dùng phải thực hiện trong phiên active liveness.
 * <p>
 * Server sinh ngẫu nhiên chuỗi hành động cho từng phiên; kẻ tấn công không đoán
 * trước được nên video quay sẵn không qua được. {@code wireValue} là giá trị
 * trao đổi với {@code finora-ai} — giữ nguyên chuỗi snake_case của hợp đồng REST.
 */
public enum LivenessAction {

    BLINK("blink"),
    TURN_LEFT("turn_left"),
    TURN_RIGHT("turn_right");

    private final String wireValue;

    LivenessAction(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }

    /** Danh sách giá trị wire tương ứng, giữ nguyên thứ tự — thứ tự là một phần của thử thách. */
    public static List<String> toWireValues(List<LivenessAction> actions) {
        return actions.stream().map(LivenessAction::getWireValue).toList();
    }

    /** Khôi phục enum từ giá trị đã lưu trong Redis; bỏ qua giá trị lạ do đổi phiên bản. */
    public static Optional<LivenessAction> fromName(String name) {
        return Arrays.stream(values())
                .filter(action -> action.name().equals(name))
                .findFirst();
    }
}
