package com.finora.user.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Thử thách liveness ngẫu nhiên cấp cho một lần quay (EKYC-002 mục 3.4).
 *
 * @param sessionId      mã phiên, dùng đúng một lần khi gửi video
 * @param expiresAt      hết hạn sau 90 giây kể từ lúc cấp
 * @param maxDurationSec giới hạn thời lượng quay = làm tròn lên tổng thời gian giữ + 3 giây
 * @param steps          lịch tư thế: FRONTAL xen giữa ba hành động khác nhau
 * @param video          cấu hình quay gợi ý cho {@code CameraView}
 */
public record LivenessChallengeResponse(
        UUID sessionId,
        Instant expiresAt,
        int maxDurationSec,
        List<Step> steps,
        VideoSettings video
) {

    public record Step(String action, int holdMs) {
    }

    /** {@code mute=true} để không phải xin quyền micro; bitrate đủ nét cho YuNet ở 4:3. */
    public record VideoSettings(String quality, int bitrate, boolean mute) {
        public static final VideoSettings DEFAULT = new VideoSettings("4:3", 1_500_000, true);
    }
}
