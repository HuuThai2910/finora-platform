package com.finora.user.integration.ai.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.finora.common.security.service.ClientCredentialsTokenProvider;
import com.finora.user.config.AiServiceProperties;
import com.finora.user.config.ServiceAccountConfig;
import com.finora.user.util.ExternalCallFailures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Client gọi bước kiểm tra khuôn mặt của {@code finora-ai}: {@code POST /api/v1/ai/ekyc/liveness}
 * dạng multipart (hợp đồng EKYC-002 mục 2.2).
 * <p>
 * Gửi multipart vì phải gửi video tới 10 MB dạng file part; base64 trong JSON làm phình dữ liệu 33% và giữ
 * cả chuỗi lớn trong bộ nhớ. Timeout đọc 60 giây: AI chỉ phân tích một video tại một thời điểm, request
 * đến khi AI bận nhận 503 ngay chứ không xếp hàng.
 * <p>
 * Gửi access token service account của finora-user (client role {@code ai:ekyc:ocr}, D34/D35) bằng interceptor
 * {@link ServiceAccountConfig#BEARER_INTERCEPTOR} ({@link ClientCredentialsTokenProvider#bearerInterceptor()}): AI trả
 * 401 thì token cũ bị bỏ để lần sau lấy mới.
 * <p>
 * Đi qua circuit breaker {@value #CIRCUIT_BREAKER} chung với bước đọc thẻ: circuit đang mở thì báo
 * {@link AiLivenessException} "không phục vụ được" như khi AI sập.
 * <p>
 * Không log nội dung part, embedding hay khung hình — chỉ mã trạng thái HTTP.
 */
@Slf4j
@Component
public class AiLivenessClient {

    /** Cùng circuit breaker với {@link AiEkycClient}: cùng một hệ thống finora-ai. */
    public static final String CIRCUIT_BREAKER = AiEkycClient.CIRCUIT_BREAKER;

    static final String PATH = "/api/v1/ai/ekyc/liveness";

    private final RestClient restClient;
    private final AiServiceProperties aiServiceProperties;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Autowired
    public AiLivenessClient(RestClient.Builder builder, AiServiceProperties aiServiceProperties, ObjectMapper objectMapper,
            @Qualifier(ServiceAccountConfig.BEARER_INTERCEPTOR) ClientHttpRequestInterceptor serviceAccountBearerInterceptor,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this(builder.clone()
                .baseUrl(aiServiceProperties.getUrl())
                .requestFactory(requestFactory(aiServiceProperties))
                .requestInterceptor(serviceAccountBearerInterceptor)
                .build(), aiServiceProperties, objectMapper, circuitBreakerFactory);
    }

    /** Dùng trong test để gắn {@code MockRestServiceServer}. */
    AiLivenessClient(RestClient restClient, AiServiceProperties aiServiceProperties, ObjectMapper objectMapper,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.restClient = restClient;
        this.aiServiceProperties = aiServiceProperties;
        this.objectMapper = objectMapper;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    private static SimpleClientHttpRequestFactory requestFactory(AiServiceProperties aiServiceProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) aiServiceProperties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) aiServiceProperties.getLivenessReadTimeout().toMillis());
        return factory;
    }

    /**
     * Gửi video (hoặc chuỗi ảnh tĩnh) cùng lịch thử thách và embedding chân dung để AI chấm.
     *
     * @throws AiLivenessException AI từ chối đầu vào (4xx) hoặc không phục vụ được (5xx, timeout, lỗi mạng, circuit
     *                             breaker đang mở)
     */
    public LivenessResult analyze(LivenessSpec spec, LivenessMedia media) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("spec", toJson(spec), MediaType.APPLICATION_JSON);
        if (media.video() != null) {
            body.part("video", new ByteArrayResource(media.video()), MediaType.parseMediaType(media.videoContentType()))
                    .filename(media.videoFilename());
        }
        for (int i = 0; i < media.frames().size(); i++) {
            body.part("frames", new ByteArrayResource(media.frames().get(i)), MediaType.IMAGE_JPEG)
                    .filename("frame-" + (i + 1) + ".jpg");
        }
        LivenessResult result = circuitBreakerFactory.create(CIRCUIT_BREAKER).run(
                () -> restClient.post()
                        .uri(PATH)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body.build())
                        .retrieve()
                        .body(LivenessResult.class),
                failure -> {
                    throw toAiLivenessException(failure);
                });
        if (result == null || result.decision() == null) {
            throw new AiLivenessException("AI trả kết quả liveness rỗng", false, 200);
        }
        return result;
    }

    /** Chạy ở luồng gọi; circuit breaker mở hoặc time limiter cắt được xử lý như không kết nối được AI. */
    private static RuntimeException toAiLivenessException(Throwable failure) {
        Throwable cause = ExternalCallFailures.cause(failure);
        if (cause instanceof RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                log.error("AI từ chối token service account khi chấm liveness (HTTP {}) — kiểm tra finora-user-client "
                        + "có client role ai:ekyc:ocr", status);
            }
            boolean invalidInput = status == 400 || status == 413 || status == 422;
            return new AiLivenessException("AI trả HTTP " + status + " khi chấm liveness", invalidInput, status);
        }
        if (ExternalCallFailures.isUnavailable(cause)) {
            return new AiLivenessException("Không kết nối được AI hoặc quá thời gian chờ", false, 0);
        }
        if (cause instanceof RestClientException) {
            return new AiLivenessException("Không đọc được phản hồi liveness của AI", false, 0);
        }
        return ExternalCallFailures.unchecked(cause);
    }

    private String toJson(LivenessSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được spec liveness", e);
        }
    }

    // --- DTOs ---

    /**
     * @param steps                 lịch thử thách đã cấp cho người dùng
     * @param portraitEmbeddingB64  embedding chân dung trên thẻ để so với khuôn mặt trong video
     * @param captureMode           {@code VIDEO} hoặc {@code STILLS}
     * @param frameTimesMs          mốc thời gian từng ảnh ở chế độ STILLS; rỗng ở chế độ VIDEO
     * @param platform              {@code ios | android | web} hoặc {@code null}; AI dùng để chọn chiều quay
     *                              đầu theo nền tảng vì video camera trước của iOS có thể bị lật gương
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LivenessSpec(List<SpecStep> steps, String portraitEmbeddingB64, String captureMode,
                               List<Long> frameTimesMs, String platform) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SpecStep(String action, int holdMs) {
    }

    /** Video ({@code video} khác null) hoặc danh sách ảnh JPEG theo đúng thứ tự {@code frameTimesMs}. */
    public record LivenessMedia(byte[] video, String videoContentType, String videoFilename, List<byte[]> frames) {
        public LivenessMedia {
            frames = frames == null ? List.of() : List.copyOf(frames);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LivenessResult(
            String decision,
            List<String> reasons,
            JsonNode video,
            JsonNode faces,
            JsonNode actions,
            JsonNode identity,
            FaceMatch faceMatch,
            JsonNode quality,
            List<EvidenceFrame> evidenceFrames,
            String thresholdsVersion
    ) {
    }

    /** {@code zone}: HIGH (khớp), GRAY (chưa chắc chắn), LOW (không khớp). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FaceMatch(Double score, String zone) {
    }

    /** Khung bằng chứng AI trích từ video (rộng 480 px, JPEG) để người duyệt xem nhanh. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceFrame(String label, String jpegB64) {
    }

    /** Lời gọi liveness thất bại; {@link #isInvalidInput()} phân biệt video hỏng với AI không phục vụ được. */
    public static class AiLivenessException extends RuntimeException {
        private final boolean invalidInput;
        private final int status;

        public AiLivenessException(String message, boolean invalidInput, int status) {
            super(message);
            this.invalidInput = invalidInput;
            this.status = status;
        }

        public boolean isInvalidInput() {
            return invalidInput;
        }

        public int getStatus() {
            return status;
        }
    }
}
