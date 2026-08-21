package com.finora.user.service;

import com.finora.user.client.AiEkycClient;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycResultCode;
import com.finora.user.domain.EkycStatus;
import com.finora.user.domain.LivenessAction;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.util.CccdMatcher;
import com.finora.user.util.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kiểm tra luồng xác minh eKYC: mỗi mã kết quả trong hợp đồng phải có một ca test,
 * và các bước nặng phía sau không được chạy khi bước trước đã trượt.
 */
@ExtendWith(MockitoExtension.class)
class EkycVerificationServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String HMAC_SECRET = "test-hmac-secret-32-characters-long";
    private static final String ID_NUMBER = "079204001234";
    private static final String SESSION_ID = "session-1";
    private static final String CCCD_IMAGE = "cccd-base64";
    private static final List<String> FRAMES = List.of("frame-0", "frame-1", "frame-2");
    private static final LocalDate DOB = LocalDate.of(2000, 1, 1);
    private static final List<LivenessAction> ACTIONS =
            List.of(LivenessAction.TURN_LEFT, LivenessAction.BLINK);

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private AiEkycClient aiEkycClient;

    @Mock
    private EkycChallengeService challengeService;

    private EkycVerificationService service;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        service = new EkycVerificationService(
                userProfileRepository,
                aiEkycClient,
                challengeService,
                new CryptoProperties(HMAC_SECRET, "test-aes-secret-32-characters!!"));

        profile = UserProfile.builder()
                .id(7L)
                .keycloakUserId(USER_ID)
                .email("nguoidung@example.com")
                .fullName("Nguyễn Văn A")
                .dateOfBirth(DOB)
                .idNumberHash(CryptoUtils.hmacSha256(ID_NUMBER, HMAC_SECRET))
                .ekycStatus(EkycStatus.PENDING)
                .build();
    }

    // ── Happy path ──────────────────────────────────────────────────

    @Test
    void dungTatCaCacBuocThiHoSoDuocXacMinh() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));
        givenLivenessReturns(activeResult(true, 1));
        givenFaceMatchReturns(new AiEkycClient.FaceMatchResult(true, 0.88, 0.6));

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        assertThat(result.status()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(result.faceMatchScore()).isEqualTo(0.88);
        assertThat(result.ocrWarnings()).isEmpty();
        verify(userProfileRepository).save(profile);
        verify(challengeService).resetFaceMismatch(USER_ID);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(profile.isLivenessVerified()).isTrue();
    }

    @Test
    void hoSoDaXacMinhThiKhongGoiLaiAiService() {
        profile.markEkycVerified(0.9);
        givenProfileFound();

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        verifyNoInteractions(aiEkycClient);
        verifyNoInteractions(challengeService);
    }

    @Test
    void truongMemLechVanXacMinhNhungCoCanhBao() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "TRAN VAN B", "02/01/2000"));
        givenLivenessReturns(activeResult(true, 0));
        givenFaceMatchReturns(new AiEkycClient.FaceMatchResult(true, 0.9, 0.6));

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        assertThat(result.ocrWarnings()).containsExactlyInAnyOrder(
                CccdMatcher.WARNING_FULL_NAME_MISMATCH, CccdMatcher.WARNING_DOB_MISMATCH);
    }

    // ── Các nhánh trượt ─────────────────────────────────────────────

    @Test
    void hoSoChuaCoCccdThiDungNgayVaKhongTonSuatGoi() {
        profile.setIdNumberHash(null);
        givenProfileFound();

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.PROFILE_NO_CCCD);
        assertThat(result.status()).isEqualTo(EkycStatus.PENDING);
        verifyNoInteractions(aiEkycClient);
        verify(challengeService, never()).tryAcquireVerifySlot(any());
    }

    @Test
    void goiQuaNhanhThiBiChan() {
        givenProfileFound();
        when(challengeService.tryAcquireVerifySlot(USER_ID)).thenReturn(false);

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.RATE_LIMITED);
        verifyNoInteractions(aiEkycClient);
    }

    @Test
    void phienHetHanThiKhongChayOcr() {
        givenProfileFound();
        givenSlotAcquired();
        when(challengeService.consumeChallenge(USER_ID, SESSION_ID)).thenReturn(Optional.empty());

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.CHALLENGE_EXPIRED);
        verifyNoInteractions(aiEkycClient);
    }

    @Test
    void ocrKhongDocDuocSoCccdThiChoChupLai() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(false, null, null, null));

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.OCR_FAILED);
        assertThat(result.status()).isEqualTo(EkycStatus.PENDING);
        verify(aiEkycClient, never()).activeLiveness(any());
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void soCccdTrenAnhKhacHoSoThiChan() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, "079204009999", "NGUYEN VAN A", "01/01/2000"));

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.ID_MISMATCH);
        verify(aiEkycClient, never()).activeLiveness(any());
    }

    @Test
    void khongDungDongTacThiKhongChayNhanDangKhuonMat() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));
        givenLivenessReturns(activeResult(false, null));

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.LIVENESS_FAILED);
        verify(aiEkycClient, never()).faceMatch(any());
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void saiKhuonMatLanDauVanGiuTrangThaiCu() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));
        givenLivenessReturns(activeResult(true, 0));
        givenFaceMatchReturns(new AiEkycClient.FaceMatchResult(false, 0.31, 0.6));
        when(challengeService.recordFaceMismatch(USER_ID)).thenReturn(1);

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.FACE_MISMATCH);
        assertThat(result.status()).isEqualTo(EkycStatus.PENDING);
        assertThat(result.livenessVerified()).isTrue();
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void saiKhuonMatLienTiepDuLanThiChuyenXetDuyetThuCong() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));
        givenLivenessReturns(activeResult(true, 0));
        givenFaceMatchReturns(new AiEkycClient.FaceMatchResult(false, 0.28, 0.6));
        when(challengeService.recordFaceMismatch(USER_ID))
                .thenReturn(EkycChallengeService.MAX_FACE_FAILURES);

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.FACE_MISMATCH);
        assertThat(result.status()).isEqualTo(EkycStatus.MANUAL_REVIEW);
        verify(userProfileRepository).save(profile);
    }

    @Test
    void aiServiceLoiThiGiuNguyenTrangThaiHoSo() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        when(aiEkycClient.ocr(any())).thenThrow(new IllegalStateException("connection refused"));

        EkycResultResponse result = service.verify(USER_ID, request());

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.AI_UNAVAILABLE);
        assertThat(result.status()).isEqualTo(EkycStatus.PENDING);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.PENDING);
        verify(userProfileRepository, never()).save(any());
    }

    // ── Chọn frame cho bước so khớp khuôn mặt ───────────────────────

    @Test
    void dungDungFrameMaAiDanhGiaLaTotNhat() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));
        givenLivenessReturns(activeResult(true, 2));
        givenFaceMatchReturns(new AiEkycClient.FaceMatchResult(true, 0.9, 0.6));

        service.verify(USER_ID, request());

        ArgumentCaptor<AiEkycClient.FaceMatchInput> input =
                ArgumentCaptor.forClass(AiEkycClient.FaceMatchInput.class);
        verify(aiEkycClient).faceMatch(input.capture());
        assertThat(input.getValue().selfie_base64()).isEqualTo(FRAMES.get(2));
        assertThat(input.getValue().cccd_image_base64()).isEqualTo(CCCD_IMAGE);
    }

    @Test
    void chiSoFrameKhongHopLeThiDungFrameDauTien() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));
        givenLivenessReturns(activeResult(true, 99));
        givenFaceMatchReturns(new AiEkycClient.FaceMatchResult(true, 0.9, 0.6));

        service.verify(USER_ID, request());

        ArgumentCaptor<AiEkycClient.FaceMatchInput> input =
                ArgumentCaptor.forClass(AiEkycClient.FaceMatchInput.class);
        verify(aiEkycClient).faceMatch(input.capture());
        assertThat(input.getValue().selfie_base64()).isEqualTo(FRAMES.get(0));
    }

    @Test
    void guiDungChuoiHanhDongCuaPhienSangAiService() {
        givenProfileFound();
        givenSlotAcquired();
        givenChallengeConsumed();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));
        givenLivenessReturns(activeResult(true, 0));
        givenFaceMatchReturns(new AiEkycClient.FaceMatchResult(true, 0.9, 0.6));

        service.verify(USER_ID, request());

        ArgumentCaptor<AiEkycClient.ActiveLivenessInput> input =
                ArgumentCaptor.forClass(AiEkycClient.ActiveLivenessInput.class);
        verify(aiEkycClient).activeLiveness(input.capture());
        assertThat(input.getValue().expected_actions()).containsExactly("turn_left", "blink");
        assertThat(input.getValue().frames()).isEqualTo(FRAMES);
    }

    // ── Helper ──────────────────────────────────────────────────────

    private EkycVerifyRequest request() {
        return new EkycVerifyRequest(SESSION_ID, FRAMES, CCCD_IMAGE);
    }

    private void givenProfileFound() {
        when(userProfileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile));
    }

    private void givenSlotAcquired() {
        when(challengeService.tryAcquireVerifySlot(USER_ID)).thenReturn(true);
    }

    private void givenChallengeConsumed() {
        when(challengeService.consumeChallenge(USER_ID, SESSION_ID))
                .thenReturn(Optional.of(ACTIONS));
    }

    private void givenOcrReturns(AiEkycClient.OcrResult result) {
        when(aiEkycClient.ocr(eq(new AiEkycClient.OcrInput(CCCD_IMAGE)))).thenReturn(result);
    }

    private void givenLivenessReturns(AiEkycClient.ActiveLivenessResult result) {
        when(aiEkycClient.activeLiveness(any())).thenReturn(result);
    }

    private void givenFaceMatchReturns(AiEkycClient.FaceMatchResult result) {
        when(aiEkycClient.faceMatch(any())).thenReturn(result);
    }

    private AiEkycClient.OcrResult ocrResult(
            boolean success, String idNumber, String fullName, String dateOfBirth) {
        return new AiEkycClient.OcrResult(
                success, idNumber, fullName, dateOfBirth, "Nam", "TP HCM", 0.91);
    }

    private AiEkycClient.ActiveLivenessResult activeResult(boolean isLive, Integer bestFrameIndex) {
        return new AiEkycClient.ActiveLivenessResult(
                isLive,
                List.of(new AiEkycClient.ActionCheck("turn_left", isLive, "stub")),
                isLive ? 0.8 : 0.25,
                "mediapipe_facemesh",
                bestFrameIndex,
                new AiEkycClient.LivenessResult(isLive, 0.7, "lbp_texture"));
    }
}
