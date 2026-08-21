package com.finora.user.service;

import com.finora.user.client.AiEkycClient;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycResultCode;
import com.finora.user.domain.EkycStatus;
import com.finora.user.domain.Gender;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.support.CryptoUtils;
import com.finora.user.util.CccdMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra luồng xác minh eKYC bằng ảnh hai mặt CCCD (không còn face/liveness). */
@ExtendWith(MockitoExtension.class)
class EkycVerificationServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String ID_NUMBER = "036094001234";
    private static final String OTHER_ID = "036094009999";
    private static final String HMAC_SECRET = "test-hmac-secret";
    private static final LocalDate DOB = LocalDate.of(2000, 1, 1);

    private static final EkycVerifyRequest REQUEST =
            new EkycVerifyRequest("front-base64", "back-base64");

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private AiEkycClient aiEkycClient;

    @Mock
    private EkycRateLimitService rateLimitService;

    private EkycVerificationService service;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        CryptoProperties cryptoProperties = new CryptoProperties();
        cryptoProperties.setHmacSecret(HMAC_SECRET);
        cryptoProperties.setAesSecret("test-aes-secret-32-characters!!");

        service = new EkycVerificationService(
                userProfileRepository, aiEkycClient, rateLimitService, cryptoProperties);

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
    void ocrKhopSoCccdThiHoSoDuocXacMinh() {
        givenProfileFound();
        givenSlotAcquired();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        assertThat(result.status()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(result.ocrWarnings()).isEmpty();
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(profile.isDocumentVerified()).isTrue();
        assertThat(profile.getEkycCompletedAt()).isNotNull();
        verify(userProfileRepository).save(profile);
    }

    @Test
    void hoSoChuaCoSoCccdThiLaySoTuOcrDienVao() {
        profile.setIdNumberHash(null);
        profile.setDateOfBirth(null);
        givenProfileFound();
        givenSlotAcquired();
        when(userProfileRepository.existsByIdNumberHash(
                CryptoUtils.hmacSha256(ID_NUMBER, HMAC_SECRET))).thenReturn(false);
        givenOcrReturns(new AiEkycClient.OcrResult(
                true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000", "Nam", "Nam Định",
                "25 Nguyễn Trãi, Thanh Xuân, Hà Nội", 0.95));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        assertThat(profile.getIdNumberHash())
                .isEqualTo(CryptoUtils.hmacSha256(ID_NUMBER, HMAC_SECRET));
        assertThat(profile.getIdNumberEncrypted()).isEqualTo(ID_NUMBER);
        assertThat(profile.getDateOfBirth()).isEqualTo(DOB);
        assertThat(profile.getGender()).isEqualTo(Gender.MALE);
        assertThat(profile.getPlaceOfOrigin()).isEqualTo("Nam Định");
        assertThat(profile.getAddress()).isEqualTo("25 Nguyễn Trãi, Thanh Xuân, Hà Nội");
        verify(userProfileRepository).save(profile);
    }

    @Test
    void hoSoDaKhaiCccdVanDuocDienTruongMemConThieu() {
        // Hồ sơ có sẵn số CCCD (đã khai) nhưng thiếu giới tính/quê quán/địa chỉ
        givenProfileFound();
        givenSlotAcquired();
        givenOcrReturns(new AiEkycClient.OcrResult(
                true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000", "Nữ", "Hà Nội",
                "12 Lý Thường Kiệt, Hoàn Kiếm, Hà Nội", 0.95));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        assertThat(profile.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(profile.getPlaceOfOrigin()).isEqualTo("Hà Nội");
        assertThat(profile.getAddress()).isEqualTo("12 Lý Thường Kiệt, Hoàn Kiếm, Hà Nội");
        // Ngày sinh đã khai từ trước — không bị OCR ghi đè
        assertThat(profile.getDateOfBirth()).isEqualTo(DOB);
    }

    @Test
    void tenHoacNgaySinhLechThiVanXacMinhNhungKemCanhBao() {
        givenProfileFound();
        givenSlotAcquired();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "TRAN VAN B", "02/02/1999"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        assertThat(result.ocrWarnings()).containsExactlyInAnyOrder(
                CccdMatcher.WARNING_FULL_NAME_MISMATCH, CccdMatcher.WARNING_DOB_MISMATCH);
    }

    // ── Các nhánh từ chối ───────────────────────────────────────────

    @Test
    void ocrKhongDocDuocThiTraOcrFailed() {
        givenProfileFound();
        givenSlotAcquired();
        givenOcrReturns(ocrResult(false, null, null, null));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.OCR_FAILED);
        assertThat(result.status()).isEqualTo(EkycStatus.PENDING);
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void soCccdTrenAnhKhacHoSoThiTraIdMismatch() {
        givenProfileFound();
        givenSlotAcquired();
        givenOcrReturns(ocrResult(true, OTHER_ID, "NGUYEN VAN A", "01/01/2000"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.ID_MISMATCH);
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void soCccdDaThuocTaiKhoanKhacThiTraIdTaken() {
        profile.setIdNumberHash(null);
        givenProfileFound();
        givenSlotAcquired();
        when(userProfileRepository.existsByIdNumberHash(
                CryptoUtils.hmacSha256(ID_NUMBER, HMAC_SECRET))).thenReturn(true);
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN VAN A", "01/01/2000"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.ID_TAKEN);
        assertThat(profile.getIdNumberHash()).isNull();
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void goiQuaNhanhThiTraRateLimitedVaKhongGoiAi() {
        givenProfileFound();
        when(rateLimitService.tryAcquireVerifySlot(USER_ID)).thenReturn(false);

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.RATE_LIMITED);
        verify(aiEkycClient, never()).ocr(any());
    }

    @Test
    void hoSoDaXacMinhThiTraVerifiedNgayKhongGoiAi() {
        profile.setEkycStatus(EkycStatus.VERIFIED);
        givenProfileFound();

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        verify(aiEkycClient, never()).ocr(any());
        verify(rateLimitService, never()).tryAcquireVerifySlot(any());
    }

    @Test
    void aiLoiThiTraAiUnavailableVaGiuNguyenHoSo() {
        givenProfileFound();
        givenSlotAcquired();
        when(aiEkycClient.ocr(any())).thenThrow(new RuntimeException("connection refused"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.AI_UNAVAILABLE);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.PENDING);
        verify(userProfileRepository, never()).save(any());
    }

    // ── Helper ──────────────────────────────────────────────────────

    private void givenProfileFound() {
        when(userProfileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile));
    }

    private void givenSlotAcquired() {
        when(rateLimitService.tryAcquireVerifySlot(USER_ID)).thenReturn(true);
    }

    private void givenOcrReturns(AiEkycClient.OcrResult result) {
        when(aiEkycClient.ocr(any())).thenReturn(result);
    }

    private static AiEkycClient.OcrResult ocrResult(
            boolean success, String idNumber, String fullName, String dob) {
        return new AiEkycClient.OcrResult(success, idNumber, fullName, dob, null, null, null, 0.9);
    }
}
