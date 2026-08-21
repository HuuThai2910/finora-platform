package com.finora.user.service;

import com.finora.user.domain.LivenessAction;
import com.finora.user.dto.response.LivenessChallengeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra vòng đời phiên challenge và các bộ đếm trên Redis. */
@ExtendWith(MockitoExtension.class)
class EkycChallengeServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String SESSION_ID = "session-fixed";
    private static final String CHALLENGE_KEY = "ekyc_challenge:" + USER_ID;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private EkycChallengeService service;

    @BeforeEach
    void setUp() {
        // Seed cố định để chuỗi hành động sinh ra là xác định được
        service = new EkycChallengeService(redisTemplate, () -> SESSION_ID, new Random(42));
    }

    @Test
    void taoChallengeLuuVaoRedisVaTraDuHaiHanhDong() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        LivenessChallengeResponse response = service.createChallenge(USER_ID);

        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.actions()).hasSize(2).doesNotHaveDuplicates();
        assertThat(response.expiresInSeconds())
                .isEqualTo(EkycChallengeService.CHALLENGE_TTL.toSeconds());

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(CHALLENGE_KEY), value.capture(), eq(EkycChallengeService.CHALLENGE_TTL));
        assertThat(value.getValue()).startsWith(SESSION_ID + "|");
    }

    @Test
    void hanhDongTraVeDungGiaTriHopDongVoiAiService() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        List<String> actions = service.createChallenge(USER_ID).actions();

        assertThat(actions).allMatch(action ->
                List.of("blink", "turn_left", "turn_right").contains(action));
    }

    @Test
    void tieuThuChallengeDungSessionThiTraVeChuoiHanhDong() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(CHALLENGE_KEY))
                .thenReturn(SESSION_ID + "|TURN_LEFT,BLINK");

        Optional<List<LivenessAction>> actions = service.consumeChallenge(USER_ID, SESSION_ID);

        assertThat(actions).contains(List.of(LivenessAction.TURN_LEFT, LivenessAction.BLINK));
    }

    @Test
    void challengeDaDungHoacHetHanThiRong() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(CHALLENGE_KEY)).thenReturn(null);

        assertThat(service.consumeChallenge(USER_ID, SESSION_ID)).isEmpty();
    }

    @Test
    void sessionIdKhongKhopThiRong() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(CHALLENGE_KEY))
                .thenReturn("session-khac|TURN_LEFT,BLINK");

        assertThat(service.consumeChallenge(USER_ID, SESSION_ID)).isEmpty();
    }

    @Test
    void goiLanHaiKhongDungLaiDuocChallengeCu() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(CHALLENGE_KEY))
                .thenReturn(SESSION_ID + "|BLINK,TURN_RIGHT")
                .thenReturn(null);

        assertThat(service.consumeChallenge(USER_ID, SESSION_ID)).isPresent();
        assertThat(service.consumeChallenge(USER_ID, SESSION_ID)).isEmpty();
    }

    @Test
    void chiemDuocSuatKhiChuaCoLanGoiTruoc() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                anyString(), eq("1"), eq(EkycChallengeService.VERIFY_MIN_INTERVAL)))
                .thenReturn(true);

        assertThat(service.tryAcquireVerifySlot(USER_ID)).isTrue();
    }

    @Test
    void goiQuaNhanhThiKhongChiemDuocSuat() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThat(service.tryAcquireVerifySlot(USER_ID)).isFalse();
    }

    @Test
    void lanSaiKhuonMatDauTienDatThoiHanChoBoDem() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertThat(service.recordFaceMismatch(USER_ID)).isEqualTo(1);
        verify(redisTemplate).expire(eq("ekyc_face_fail:" + USER_ID), any(Duration.class));
    }

    @Test
    void nhungLanSaiSauKhongGiaHanBoDem() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(2L);

        assertThat(service.recordFaceMismatch(USER_ID)).isEqualTo(2);
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }
}
