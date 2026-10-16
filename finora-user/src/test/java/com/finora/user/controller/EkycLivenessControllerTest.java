package com.finora.user.controller;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.user.dto.response.EkycNextStep;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.LivenessChallengeResponse;
import com.finora.user.service.EkycLivenessService;
import com.finora.user.service.EkycLivenessService.LivenessUpload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finora.user.controller.ControllerTestSupport.USER_ID;
import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Biên HTTP của thử thách liveness và endpoint multipart gửi video (EKYC-002 mục 3.4). */
@ExtendWith(MockitoExtension.class)
class EkycLivenessControllerTest {

    private static final String BASE = "/api/v1/users/profile/ekyc/liveness";

    @Mock
    private EkycLivenessService livenessService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new com.finora.user.controller.EkycLivenessController(livenessService));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void challengeReturnsContractShape() throws Exception {
        authenticateWith("user:cccd:scan");
        UUID session = UUID.fromString("99999999-8888-7777-6666-555555555555");
        when(livenessService.createChallenge(USER_ID)).thenReturn(new LivenessChallengeResponse(session,
                Instant.parse("2026-10-16T10:01:30Z"), 12,
                List.of(new LivenessChallengeResponse.Step("FRONTAL", 1500), new LivenessChallengeResponse.Step("TURN_LEFT", 1300)),
                LivenessChallengeResponse.VideoSettings.DEFAULT));

        mockMvc.perform(post(BASE + "/challenge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(session.toString()))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-16T10:01:30Z"))
                .andExpect(jsonPath("$.data.maxDurationSec").value(12))
                .andExpect(jsonPath("$.data.steps[1].action").value("TURN_LEFT"))
                .andExpect(jsonPath("$.data.steps[1].holdMs").value(1300))
                .andExpect(jsonPath("$.data.video.quality").value("4:3"))
                .andExpect(jsonPath("$.data.video.bitrate").value(1500000))
                .andExpect(jsonPath("$.data.video.mute").value(true));
    }

    @Test
    void multipartVideoIsPassedToServiceAndResultSerialized() throws Exception {
        authenticateWith("user:cccd:scan");
        when(livenessService.submit(eq(USER_ID), any())).thenReturn(new EkycResultResponse(EkycStatus.PENDING,
                EkycResultCode.LIVENESS_PASSED, "Kiểm tra khuôn mặt thành công.", List.of(), "CCCD_CHIP_2021", false,
                List.of(), null, EkycNextStep.CONFIRM, null,
                new EkycResultResponse.LivenessView("PASSED", 0.52, List.of())));
        byte[] video = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

        mockMvc.perform(multipart(BASE)
                        .file(new MockMultipartFile("video", "liveness.mp4", "video/mp4", video))
                        .param("sessionId", "99999999-8888-7777-6666-555555555555")
                        .param("captureMode", "VIDEO")
                        .param("platform", "android"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultCode").value("LIVENESS_PASSED"))
                .andExpect(jsonPath("$.data.nextStep").value("CONFIRM"))
                .andExpect(jsonPath("$.data.attemptsLeft").isEmpty())
                .andExpect(jsonPath("$.data.draft").isEmpty())
                .andExpect(jsonPath("$.data.liveness.result").value("PASSED"))
                .andExpect(jsonPath("$.data.liveness.faceMatchScore").value(0.52));

        ArgumentCaptor<LivenessUpload> upload = ArgumentCaptor.forClass(LivenessUpload.class);
        verify(livenessService).submit(eq(USER_ID), upload.capture());
        assertThat(upload.getValue().sessionId()).isEqualTo("99999999-8888-7777-6666-555555555555");
        assertThat(upload.getValue().captureMode()).isEqualTo("VIDEO");
        assertThat(upload.getValue().video().bytes()).isEqualTo(video);
        assertThat(upload.getValue().video().contentType()).isEqualTo("video/mp4");
        assertThat(upload.getValue().frames()).isEmpty();
    }

    @Test
    void stillFramesArriveInOrderWithTimes() throws Exception {
        authenticateWith("user:cccd:scan");
        when(livenessService.submit(eq(USER_ID), any())).thenReturn(new EkycResultResponse(EkycStatus.PENDING,
                EkycResultCode.LIVENESS_REVIEW, "m", List.of(), null, true, List.of(), null, EkycNextStep.CONFIRM,
                null, null));

        mockMvc.perform(multipart(BASE)
                        .file(new MockMultipartFile("frames", "f1.jpg", "image/jpeg", new byte[]{1}))
                        .file(new MockMultipartFile("frames", "f2.jpg", "image/jpeg", new byte[]{2}))
                        .param("sessionId", "s")
                        .param("captureMode", "STILLS")
                        .param("frameTimesMs", "[0,1000]"))
                .andExpect(status().isOk());

        ArgumentCaptor<LivenessUpload> upload = ArgumentCaptor.forClass(LivenessUpload.class);
        verify(livenessService).submit(eq(USER_ID), upload.capture());
        assertThat(upload.getValue().frames()).extracting(file -> file.bytes()[0]).containsExactly((byte) 1, (byte) 2);
        assertThat(upload.getValue().frameTimesMs()).isEqualTo("[0,1000]");
        assertThat(upload.getValue().video()).isNull();
    }

    @Test
    void oversizedUploadMapsTo413() throws Exception {
        authenticateWith("user:cccd:scan");
        when(livenessService.submit(eq(USER_ID), any())).thenThrow(new MaxUploadSizeExceededException(12L * 1024 * 1024));

        mockMvc.perform(multipart(BASE)
                        .file(new MockMultipartFile("video", "liveness.mp4", "video/mp4", new byte[]{1}))
                        .param("sessionId", "s"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void withoutScanAuthorityIsForbidden() throws Exception {
        authenticateWith("user:profile:read");

        mockMvc.perform(multipart(BASE)
                        .file(new MockMultipartFile("video", "liveness.mp4", "video/mp4", new byte[]{1}))
                        .param("sessionId", "s"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post(BASE + "/challenge"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(livenessService);
    }
}
