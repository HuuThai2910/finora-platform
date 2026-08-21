package com.finora.user.service;

import com.finora.user.client.AiEkycClient;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycResultCode;
import com.finora.user.domain.EkycStatus;
import com.finora.user.domain.Gender;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.EkycResultResponse.EkycDraft;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra luồng eKYC hai bước: quét ra bản nháp → xác nhận mới lưu. */
@ExtendWith(MockitoExtension.class)
class EkycVerificationServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String ID_NUMBER = "036094001234";
    private static final String OTHER_ID = "036094009999";
    private static final String HMAC_SECRET = "test-hmac-secret";
    private static final LocalDate DOB = LocalDate.of(2000, 1, 1);

    private static final EkycVerifyRequest REQUEST =
            new EkycVerifyRequest("front-base64", "back-base64");

    private static final EkycDraft DRAFT = new EkycDraft(
            ID_NUMBER, "NGUYEN HUYNH NGOC HAI", "24/08/2004", "Nam",
            "Phường 2, Gò Công, Tiền Giang", "202 A, Đường 12, KP5, Gò Công, Tiền Giang");

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private AiEkycClient aiEkycClient;

    @Mock
    private EkycRateLimitService rateLimitService;

    @Mock
    private EkycDraftStore draftStore;

    private EkycVerificationService service;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        CryptoProperties cryptoProperties = new CryptoProperties();
        cryptoProperties.setHmacSecret(HMAC_SECRET);
        cryptoProperties.setAesSecret("test-aes-secret-32-characters!!");

        service = new EkycVerificationService(
                userProfileRepository, aiEkycClient, rateLimitService, draftStore, cryptoProperties);

        // Đăng ký không thu họ tên — hồ sơ khởi đầu trống thông tin định danh
        profile = UserProfile.builder()
                .id(7L)
                .keycloakUserId(USER_ID)
                .email("nguoidung@example.com")
                .ekycStatus(EkycStatus.PENDING)
                .build();
    }

    // ── Bước quét ───────────────────────────────────────────────────

    @Test
    void quetThanhCongThiTraBanNhapVaChuaLuuHoSo() {
        givenProfileFound();
        givenSlotAcquired();
        when(userProfileRepository.existsByIdNumberHash(hash(ID_NUMBER))).thenReturn(false);
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN HUYNH NGOC HAI", "24/08/2004"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.DRAFT_READY);
        assertThat(result.draft().idNumber()).isEqualTo(ID_NUMBER);
        assertThat(result.draft().fullName()).isEqualTo("NGUYEN HUYNH NGOC HAI");
        verify(draftStore).save(eq(USER_ID), any(EkycDraft.class));
        // Hồ sơ tuyệt đối chưa được ghi ở bước quét
        verify(userProfileRepository, never()).save(any());
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.PENDING);
    }

    @Test
    void ocrKhongDocDuocThiTraOcrFailedVaKhongTaoNhap() {
        givenProfileFound();
        givenSlotAcquired();
        givenOcrReturns(ocrResult(false, null, null, null));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.OCR_FAILED);
        verify(draftStore, never()).save(any(), any());
    }

    @Test
    void soCccdDaThuocTaiKhoanKhacThiTraIdTaken() {
        givenProfileFound();
        givenSlotAcquired();
        when(userProfileRepository.existsByIdNumberHash(hash(ID_NUMBER))).thenReturn(true);
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN HUYNH NGOC HAI", "24/08/2004"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.ID_TAKEN);
        verify(draftStore, never()).save(any(), any());
    }

    @Test
    void hoSoCoSoCccdCuKhacAnhThiTraIdMismatch() {
        profile.setIdNumberHash(hash(OTHER_ID));
        givenProfileFound();
        givenSlotAcquired();
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN HUYNH NGOC HAI", "24/08/2004"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.ID_MISMATCH);
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
    void aiLoiThiTraAiUnavailable() {
        givenProfileFound();
        givenSlotAcquired();
        when(aiEkycClient.ocr(any())).thenThrow(new RuntimeException("connection refused"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.AI_UNAVAILABLE);
    }

    @Test
    void hoSoDaXacMinhThiKhongQuetLai() {
        profile.setEkycStatus(EkycStatus.VERIFIED);
        givenProfileFound();

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        verify(aiEkycClient, never()).ocr(any());
    }

    // ── Bước xác nhận ───────────────────────────────────────────────

    @Test
    void xacNhanThiLuuBanNhapVaoHoSoVaChuyenVerified() {
        givenProfileFound();
        when(draftStore.find(USER_ID)).thenReturn(Optional.of(DRAFT));
        when(userProfileRepository.existsByIdNumberHash(hash(ID_NUMBER))).thenReturn(false);

        EkycResultResponse result = service.confirm(USER_ID);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(profile.isDocumentVerified()).isTrue();
        assertThat(profile.getIdNumberHash()).isEqualTo(hash(ID_NUMBER));
        assertThat(profile.getIdNumberEncrypted()).isEqualTo(ID_NUMBER);
        assertThat(profile.getFullName()).isEqualTo("NGUYEN HUYNH NGOC HAI");
        assertThat(profile.getDateOfBirth()).isEqualTo(LocalDate.of(2004, 8, 24));
        assertThat(profile.getGender()).isEqualTo(Gender.MALE);
        assertThat(profile.getPlaceOfOrigin()).isEqualTo("Phường 2, Gò Công, Tiền Giang");
        assertThat(profile.getAddress()).startsWith("202 A");
        verify(userProfileRepository).save(profile);
        verify(draftStore).remove(USER_ID);
    }

    @Test
    void banNhapHetHanThiTraDraftExpiredVaKhongLuu() {
        givenProfileFound();
        when(draftStore.find(USER_ID)).thenReturn(Optional.empty());

        EkycResultResponse result = service.confirm(USER_ID);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.DRAFT_EXPIRED);
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void xacNhanKhongGhiDeDuLieuDaCoTrongHoSo() {
        // Hồ sơ đã có ngày sinh khai từ trước — bản nháp không được ghi đè
        profile.setDateOfBirth(DOB);
        givenProfileFound();
        when(draftStore.find(USER_ID)).thenReturn(Optional.of(DRAFT));
        when(userProfileRepository.existsByIdNumberHash(hash(ID_NUMBER))).thenReturn(false);

        service.confirm(USER_ID);

        assertThat(profile.getDateOfBirth()).isEqualTo(DOB);
    }

    @Test
    void soCccdBiChiemTrongLucSoatThiTuChoiVaXoaNhap() {
        givenProfileFound();
        when(draftStore.find(USER_ID)).thenReturn(Optional.of(DRAFT));
        when(userProfileRepository.existsByIdNumberHash(hash(ID_NUMBER))).thenReturn(true);

        EkycResultResponse result = service.confirm(USER_ID);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.ID_TAKEN);
        verify(userProfileRepository, never()).save(any());
        verify(draftStore).remove(USER_ID);
    }

    @Test
    void xacNhanLanHaiKhiDaVerifiedThiTraVerifiedNgay() {
        profile.setEkycStatus(EkycStatus.VERIFIED);
        givenProfileFound();

        EkycResultResponse result = service.confirm(USER_ID);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
        verify(draftStore, never()).find(any());
    }

    // ── Cảnh báo trường mềm ────────────────────────────────────────

    @Test
    void tenDaKhaiLechVoiOcrThiCanhBaoNgayTuBuocQuet() {
        profile.setFullName("Trần Văn Khác");
        profile.setDateOfBirth(DOB);
        givenProfileFound();
        givenSlotAcquired();
        when(userProfileRepository.existsByIdNumberHash(hash(ID_NUMBER))).thenReturn(false);
        givenOcrReturns(ocrResult(true, ID_NUMBER, "NGUYEN HUYNH NGOC HAI", "24/08/2004"));

        EkycResultResponse result = service.verify(USER_ID, REQUEST);

        assertThat(result.resultCode()).isEqualTo(EkycResultCode.DRAFT_READY);
        assertThat(result.ocrWarnings()).contains(
                CccdMatcher.WARNING_FULL_NAME_MISMATCH, CccdMatcher.WARNING_DOB_MISMATCH);
    }

    // ── Helper ──────────────────────────────────────────────────────

    private static String hash(String idNumber) {
        return CryptoUtils.hmacSha256(idNumber, HMAC_SECRET);
    }

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
        return new AiEkycClient.OcrResult(
                success, idNumber, fullName, dob, "Nam",
                "Phường 2, Gò Công, Tiền Giang", "202 A, Đường 12, KP5, Gò Công, Tiền Giang", 0.9);
    }
}
