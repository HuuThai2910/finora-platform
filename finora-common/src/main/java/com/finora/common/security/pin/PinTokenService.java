package com.finora.common.security.pin;

import com.finora.common.exception.BusinessException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Cấp và kiểm pin-token — JWT HS256 sống ngắn, chứng minh người dùng vừa nhập
 * đúng mã PIN cho một loại thao tác.
 * <p>
 * Dùng khoá đối xứng chung giữa finora-user (bên cấp) và các service nghiệp vụ
 * (bên kiểm) để không service nào phải gọi sang finora-user lúc xử lý giao dịch.
 * Token gắn với {@code sub} của access token Keycloak, một {@link PinScope} và đúng một thao tác: claim {@code req} là
 * SHA-256 của {@code Idempotency-Key} mà app tạo trước khi hỏi PIN (D38, W3A). Gửi lại đúng thao tác đó (mạng chập
 * chờn) vẫn qua và service trả kết quả cũ theo idempotency; thao tác khác cùng loại bị từ chối. Trước W3A một token
 * dùng lại được cho mọi lệnh cùng loại trong thời hạn sống.
 * <p>
 * Thiếu khoá thì service vẫn khởi động (finora-notification, finora-blockchain
 * không cần PIN), nhưng mọi endpoint {@link RequirePin} bị từ chối thay vì mở.
 */
public class PinTokenService {

    public static final String HEADER_NAME = "X-Pin-Token";
    /** Header mang mã thao tác mà token PIN được cấp cho. */
    public static final String REQUEST_KEY_HEADER = "Idempotency-Key";

    static final JOSEObjectType TOKEN_TYPE = new JOSEObjectType("pin+jwt");
    static final String ISSUER = "finora-user";
    static final String SCOPE_CLAIM = "pin_scope";
    static final String REQUEST_CLAIM = "req";

    private static final int MIN_SECRET_BYTES = 32;
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public PinTokenService(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.isBlank()) {
            this.secret = null;
        } else {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "finora.pin.token-secret phải dài tối thiểu " + MIN_SECRET_BYTES + " byte");
            }
            this.secret = bytes;
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Pin-token kèm thời điểm hết hạn để client biết còn bao lâu. */
    public record IssuedPinToken(String token, Instant expiresAt) {
    }

    /**
     * @param requestKey {@code Idempotency-Key} của thao tác sắp gửi; token chỉ dùng được cho đúng thao tác này
     */
    public IssuedPinToken issue(String subject, PinScope scope, String requestKey) {
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("Token PIN phải gắn với Idempotency-Key của thao tác");
        }
        byte[] key = requireSecret();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .claim(SCOPE_CLAIM, scope.name())
                .claim(REQUEST_CLAIM, requestHash(requestKey))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(TOKEN_TYPE).build(), claims);
        try {
            jwt.sign(new MACSigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Không ký được pin-token", e);
        }
        return new IssuedPinToken(jwt.serialize(), expiresAt);
    }

    /**
     * Ném {@link BusinessException} 403 nếu token không do finora-user ký, đã hết hạn,
     * thuộc người khác, cấp cho loại thao tác khác hoặc cho một thao tác khác ({@code requestKey}).
     */
    public void verify(String token, String subject, PinScope scope, String requestKey) {
        byte[] key = requireSecret();
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())
                    || !TOKEN_TYPE.equals(header.getType())
                    || !jwt.verify(new MACVerifier(key))) {
                throw invalid();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiresAt = claims.getExpirationTime();
            if (!ISSUER.equals(claims.getIssuer())
                    || subject == null
                    || !subject.equals(claims.getSubject())
                    || !scope.name().equals(claims.getStringClaim(SCOPE_CLAIM))
                    || expiresAt == null
                    || expiresAt.toInstant().plus(CLOCK_SKEW).isBefore(clock.instant())
                    || !sameRequest(claims.getStringClaim(REQUEST_CLAIM), requestKey)) {
                throw invalid();
            }
        } catch (ParseException | JOSEException e) {
            throw invalid();
        }
    }

    private static boolean sameRequest(String claimed, String requestKey) {
        if (claimed == null || requestKey == null || requestKey.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(claimed.getBytes(StandardCharsets.US_ASCII),
                requestHash(requestKey).getBytes(StandardCharsets.US_ASCII));
    }

    /** SHA-256 (base64url) của mã thao tác: token không mang nguyên Idempotency-Key. */
    static String requestHash(String requestKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(requestKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM thiếu SHA-256", e);
        }
    }

    private byte[] requireSecret() {
        if (secret == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PIN_NOT_CONFIGURED",
                    "Hệ thống chưa cấu hình xác nhận mã PIN, vui lòng thử lại sau");
        }
        return secret;
    }

    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.FORBIDDEN, "PIN_TOKEN_INVALID",
                "Xác nhận mã PIN đã hết hạn hoặc không hợp lệ, vui lòng nhập lại");
    }
}
