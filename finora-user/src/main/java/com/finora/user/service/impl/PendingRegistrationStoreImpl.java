package com.finora.user.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.PendingRegistration;
import com.finora.user.service.PendingRegistrationStore;
import com.finora.user.support.CryptoUtils;
import com.finora.user.support.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Triển khai kho đăng ký tạm bằng Redis.
 * <p>
 * TTL trùng với TTL của OTP đăng ký để hai thứ hết hạn cùng lúc — tránh trường hợp
 * OTP còn hiệu lực nhưng thông tin đăng ký đã mất (hoặc ngược lại).
 * <p>
 * Mật khẩu được mã hoá AES-GCM bằng cùng khoá đang dùng cho PII trong DB, nên
 * người đọc được Redis vẫn không lấy được mật khẩu người dùng.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PendingRegistrationStoreImpl implements PendingRegistrationStore {

    /** Thời gian sống của phiên đăng ký tạm (phút) — phải khớp TTL OTP đăng ký */
    private static final long PENDING_TTL_MINUTES = 5;

    private static final String KEY_PENDING_REGISTRATION = "pending_reg:";

    private final StringRedisTemplate redisTemplate;
    private final CryptoProperties cryptoProperties;
    private final ObjectMapper objectMapper;

    @Override
    public void save(PendingRegistration registration) {
        PendingRegistration protectedCopy = new PendingRegistration(
                registration.email(),
                CryptoUtils.encryptAesGcm(registration.password(), cryptoProperties.getAesSecret()),
                registration.fullName(),
                registration.phone(),
                registration.role());

        try {
            redisTemplate.opsForValue().set(
                    key(registration.email()),
                    objectMapper.writeValueAsString(protectedCopy),
                    Duration.ofMinutes(PENDING_TTL_MINUTES));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Lỗi tuần tự hoá thông tin đăng ký tạm", e);
        }
    }

    @Override
    public Optional<PendingRegistration> find(String email) {
        String raw = redisTemplate.opsForValue().get(key(email));
        if (raw == null) {
            return Optional.empty();
        }

        try {
            PendingRegistration stored = objectMapper.readValue(raw, PendingRegistration.class);
            return Optional.of(new PendingRegistration(
                    stored.email(),
                    CryptoUtils.decryptAesGcm(stored.password(), cryptoProperties.getAesSecret()),
                    stored.fullName(),
                    stored.phone(),
                    stored.role()));
        } catch (JsonProcessingException | IllegalStateException e) {
            // Bản ghi hỏng hoặc giải mã thất bại (khoá AES đã đổi) thì coi như không có,
            // buộc người dùng đăng ký lại thay vì trả lỗi 500 không hành động được
            log.error("Bản ghi đăng ký tạm không đọc được, đã xoá: email={}",
                    PiiMasker.maskEmail(email), e);
            remove(email);
            return Optional.empty();
        }
    }

    @Override
    public void remove(String email) {
        redisTemplate.delete(key(email));
    }

    @Override
    public long ttlSeconds() {
        return Duration.ofMinutes(PENDING_TTL_MINUTES).toSeconds();
    }

    private String key(String email) {
        return KEY_PENDING_REGISTRATION + email;
    }
}
