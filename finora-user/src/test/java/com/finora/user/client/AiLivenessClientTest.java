package com.finora.user.client;

import com.finora.user.client.AiLivenessClient.AiLivenessException;
import com.finora.user.client.AiLivenessClient.LivenessMedia;
import com.finora.user.client.AiLivenessClient.LivenessResult;
import com.finora.user.client.AiLivenessClient.LivenessSpec;
import com.finora.user.client.AiLivenessClient.SpecStep;
import com.finora.user.config.AiServiceProperties;
import com.finora.user.support.EkycTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Hợp đồng multipart với {@code POST /api/v1/ai/ekyc/liveness} (EKYC-002 mục 2.2). */
class AiLivenessClientTest {

    private static final String RESPONSE = """
            {"decision":"PASS","reasons":["ACTIONS_MATCH","FACE_MATCH_HIGH"],
             "video":{"duration_ms":7600,"fps":29.7,"pts_monotonic":true,"max_gap_ms":70,"codec":"h264"},
             "faces":{"sampled_frames":76,"single_face_ratio":0.97,"multi_face_frames":0,"min_ied_px":92.0,"track_continuous":true},
             "actions":{"expected":["TURN_LEFT"],"detected":["TURN_LEFT"],"extra_events":0,"intervals_ok":true},
             "identity":{"min_pair_cos":0.71,"frames_checked":5},
             "face_match":{"score":0.52,"zone":"HIGH"},
             "quality":{"best_sharpness":143.0,"brightness":0.52},
             "evidence_frames":[{"label":"FRONTAL","jpeg_b64":"/9j/4AAQ"}],
             "thresholds_version":"liveness-2026-10-placeholder",
             "new_field_from_future_ai":1}
            """;

    private MockRestServiceServer server;
    private AiLivenessClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        AiServiceProperties properties = new AiServiceProperties();
        properties.setInternalToken("noi-bo");
        client = new AiLivenessClient(builder.build(), properties, EkycTestData.objectMapper());
    }

    @Test
    void sendsSpecAndVideoAsMultipartWithInternalToken() {
        server.expect(requestTo("http://ai.test/api/v1/ai/ekyc/liveness"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "noi-bo"))
                .andExpect(request -> {
                    MockClientHttpRequest mock = (MockClientHttpRequest) request;
                    assertThat(mock.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
                    String body = mock.getBodyAsString(StandardCharsets.ISO_8859_1);
                    assertThat(body).contains("name=\"spec\"", "\"hold_ms\":1300", "\"portrait_embedding_b64\":\"EMB\"",
                            "\"capture_mode\":\"VIDEO\"", "\"frame_times_ms\":[]", "\"platform\":\"ios\"",
                            "name=\"video\"; filename=\"liveness.mp4\"", "Content-Type: video/mp4");
                })
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        LivenessResult result = client.analyze(
                new LivenessSpec(List.of(new SpecStep("FRONTAL", 1500), new SpecStep("TURN_LEFT", 1300)), "EMB", "VIDEO", List.of(), "ios"),
                new LivenessMedia(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p'}, "video/mp4", "liveness.mp4", List.of()));

        assertThat(result.decision()).isEqualTo("PASS");
        assertThat(result.faceMatch().score()).isEqualTo(0.52);
        assertThat(result.faceMatch().zone()).isEqualTo("HIGH");
        assertThat(result.evidenceFrames()).singleElement().satisfies(frame -> {
            assertThat(frame.label()).isEqualTo("FRONTAL");
            assertThat(frame.jpegB64()).isEqualTo("/9j/4AAQ");
        });
        assertThat(result.thresholdsVersion()).isEqualTo("liveness-2026-10-placeholder");
        server.verify();
    }

    @Test
    void sendsFramesInStillsMode() {
        server.expect(requestTo("http://ai.test/api/v1/ai/ekyc/liveness"))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.ISO_8859_1))
                        .contains("filename=\"frame-1.jpg\"", "filename=\"frame-2.jpg\"", "\"frame_times_ms\":[0,1000]"))
                .andRespond(withSuccess(RESPONSE.replace("\"PASS\"", "\"REVIEW\""), MediaType.APPLICATION_JSON));

        LivenessResult result = client.analyze(new LivenessSpec(List.of(), "EMB", "STILLS", List.of(0L, 1000L), null),
                new LivenessMedia(null, null, null, List.of(EkycTestData.JPEG_BYTES, EkycTestData.JPEG_BYTES)));

        assertThat(result.decision()).isEqualTo("REVIEW");
    }

    @Test
    void busyAiIsUnavailableNotInvalidInput() {
        server.expect(requestTo("http://ai.test/api/v1/ai/ekyc/liveness"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("{\"detail\":\"BUSY\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.analyze(new LivenessSpec(List.of(), "EMB", "VIDEO", List.of(), "android"),
                new LivenessMedia(new byte[]{1}, "video/mp4", "liveness.mp4", List.of())))
                .isInstanceOfSatisfying(AiLivenessException.class, e -> {
                    assertThat(e.isInvalidInput()).isFalse();
                    assertThat(e.getStatus()).isEqualTo(503);
                });
    }

    @Test
    void unreadableVideoIsInvalidInput() {
        server.expect(requestTo("http://ai.test/api/v1/ai/ekyc/liveness"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> client.analyze(new LivenessSpec(List.of(), "EMB", "VIDEO", List.of(), "android"),
                new LivenessMedia(new byte[]{1}, "video/mp4", "liveness.mp4", List.of())))
                .isInstanceOfSatisfying(AiLivenessException.class, e -> assertThat(e.isInvalidInput()).isTrue());
    }
}
