package com.finora.user.service;

import com.finora.user.domain.LivenessAction;
import com.finora.user.dto.response.LivenessChallengeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Quản lý trạng thái ngắn hạn của luồng xác minh eKYC trên Redis.
 * <p>
 * Ba thứ được giữ ở đây thay vì trong database:
 * <ul>
 *   <li><b>Phiên challenge</b> — chuỗi hành động ngẫu nhiên, sống 60 giây và
 *       <b>dùng đúng một lần</b>. Đây là cơ chế chặn video quay sẵn: kẻ tấn công
 *       không biết trước server sẽ yêu cầu hành động nào, theo thứ tự nào.</li>
 *   <li><b>Rate limit</b> — mỗi lần xác minh chạy OCR, FaceMesh và nhận dạng
 *       khuôn mặt nên rất tốn tài nguyên; chặn gọi dồn dập.</li>
 *   <li><b>Bộ đếm sai khuôn mặt</b> — sai liên tiếp nhiều lần là tín hiệu gian
 *       lận đáng để admin xem, nhưng không khoá người dùng.</li>
 * </ul>
 * Dữ liệu ở đây là tạm thời và tự hết hạn, mất Redis chỉ khiến người dùng phải
 * lấy challenge mới chứ không hỏng trạng thái nghiệp vụ.
 */
@Service
@Slf4j
public class EkycChallengeService {

    /** Phiên challenge sống đủ để người dùng đọc hướng dẫn và quay xong. */
    public static final Duration CHALLENGE_TTL = Duration.ofSeconds(60);

    /** Khoảng cách tối thiểu giữa hai lần gọi xác minh của cùng một người dùng. */
    public static final Duration VERIFY_MIN_INTERVAL = Duration.ofSeconds(10);

    /** Số lần sai khuôn mặt liên tiếp trước khi gắn cờ cho admin xem. */
    public static final int MAX_FACE_FAILURES = 3;

    /** Số hành động mỗi phiên — hai hành động đã đủ để bắt buộc phải có thứ tự. */
    private static final int ACTIONS_PER_CHALLENGE = 2;

    /** Bộ đếm sai khuôn mặt tự quên sau một ngày để không phạt vĩnh viễn. */
    private static final Duration FACE_FAIL_TTL = Duration.ofHours(24);

    private static final String KEY_CHALLENGE = "ekyc_challenge:";
    private static final String KEY_VERIFY_RATE = "ekyc_verify_rate:";
    private static final String KEY_FACE_FAIL = "ekyc_face_fail:";

    /** Ngăn cách sessionId và danh sách hành động trong một giá trị Redis. */
    private static final String VALUE_SEPARATOR = "|";
    private static final String ACTION_SEPARATOR = ",";

    private final StringRedisTemplate redisTemplate;
    private final Supplier<String> sessionIdGenerator;
    private final Random random;

    public EkycChallengeService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, () -> UUID.randomUUID().toString(), new SecureRandom());
    }

    /** Constructor cho test — bơm nguồn ngẫu nhiên và sinh sessionId để kết quả xác định được. */
    EkycChallengeService(
            StringRedisTemplate redisTemplate,
            Supplier<String> sessionIdGenerator,
            Random random) {
        this.redisTemplate = redisTemplate;
        this.sessionIdGenerator = sessionIdGenerator;
        this.random = random;
    }

    /**
     * Sinh phiên challenge mới cho người dùng, ghi đè phiên cũ nếu còn.
     * <p>
     * Mỗi người dùng chỉ có một phiên đang mở: lấy challenge mới đồng nghĩa huỷ
     * challenge cũ, nên không tích luỹ được nhiều phiên để thử lần lượt.
     */
    public LivenessChallengeResponse createChallenge(UUID keycloakUserId) {
        List<LivenessAction> actions = randomActions();
        String sessionId = sessionIdGenerator.get();

        redisTemplate.opsForValue().set(
                KEY_CHALLENGE + keycloakUserId,
                sessionId + VALUE_SEPARATOR + joinActions(actions),
                CHALLENGE_TTL);

        log.info("Tạo challenge liveness: userId={}, actions={}", keycloakUserId, actions);

        return new LivenessChallengeResponse(
                sessionId, LivenessAction.toWireValues(actions), CHALLENGE_TTL.toSeconds());
    }

    /**
     * Lấy và **huỷ** phiên challenge. Trả rỗng nếu phiên không tồn tại, đã hết
     * hạn, đã dùng hoặc {@code sessionId} không khớp.
     * <p>
     * Xoá ngay cả khi sessionId sai là cố ý: một phiên chỉ được dùng cho đúng
     * một lần gửi, không cho thử nhiều sessionId trên cùng một challenge.
     */
    public Optional<List<LivenessAction>> consumeChallenge(UUID keycloakUserId, String sessionId) {
        String stored = redisTemplate.opsForValue().getAndDelete(KEY_CHALLENGE + keycloakUserId);
        if (stored == null) {
            return Optional.empty();
        }

        String[] parts = stored.split("\\" + VALUE_SEPARATOR, 2);
        if (parts.length != 2 || !parts[0].equals(sessionId)) {
            log.warn("SessionId không khớp phiên đang mở: userId={}", keycloakUserId);
            return Optional.empty();
        }

        List<LivenessAction> actions = parseActions(parts[1]);
        return actions.isEmpty() ? Optional.empty() : Optional.of(actions);
    }

    /**
     * Chiếm suất gọi xác minh. Trả {@code false} nếu lần gọi trước còn trong
     * khoảng chờ — mỗi lần xác minh tốn OCR, FaceMesh và nhận dạng khuôn mặt.
     */
    public boolean tryAcquireVerifySlot(UUID keycloakUserId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                KEY_VERIFY_RATE + keycloakUserId, "1", VERIFY_MIN_INTERVAL);
        return Boolean.TRUE.equals(acquired);
    }

    /** Ghi nhận một lần sai khuôn mặt và trả về số lần sai liên tiếp hiện tại. */
    public int recordFaceMismatch(UUID keycloakUserId) {
        String key = KEY_FACE_FAIL + keycloakUserId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, FACE_FAIL_TTL);
        }
        return count != null ? count.intValue() : 1;
    }

    /** Xoá bộ đếm sau khi xác minh thành công. */
    public void resetFaceMismatch(UUID keycloakUserId) {
        redisTemplate.delete(KEY_FACE_FAIL + keycloakUserId);
    }

    private List<LivenessAction> randomActions() {
        List<LivenessAction> pool = new ArrayList<>(Arrays.asList(LivenessAction.values()));
        Collections.shuffle(pool, random);
        return List.copyOf(pool.subList(0, ACTIONS_PER_CHALLENGE));
    }

    private String joinActions(List<LivenessAction> actions) {
        return actions.stream().map(Enum::name).reduce((a, b) -> a + ACTION_SEPARATOR + b).orElse("");
    }

    private List<LivenessAction> parseActions(String raw) {
        return Arrays.stream(raw.split(ACTION_SEPARATOR))
                .map(LivenessAction::fromName)
                .flatMap(Optional::stream)
                .toList();
    }
}
