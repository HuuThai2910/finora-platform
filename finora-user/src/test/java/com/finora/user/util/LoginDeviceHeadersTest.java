package com.finora.user.util;

import com.finora.user.domain.enums.LoginClientType;
import com.finora.user.domain.model.LoginDeviceContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LoginDeviceHeadersTest {

    private static final String CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1";

    @Test
    void mobileHeadersAreUsedAsSent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Type", "mobile");
        request.addHeader(LoginDeviceHeaders.DEVICE_ID, "a1b2c3d4-e5f6-4711-8899-aabbccddeeff");
        request.addHeader(LoginDeviceHeaders.DEVICE_NAME, "iPhone 15 Pro");
        request.addHeader(LoginDeviceHeaders.DEVICE_OS, "iOS 18.1");
        request.addHeader(LoginDeviceHeaders.APP_VERSION, "1.4.0");
        request.addHeader("X-Forwarded-For", "113.161.10.20, 10.0.0.2");

        LoginDeviceContext context = LoginDeviceHeaders.from(request);

        assertThat(context.deviceId()).isEqualTo("a1b2c3d4-e5f6-4711-8899-aabbccddeeff");
        assertThat(context.deviceName()).isEqualTo("iPhone 15 Pro");
        assertThat(context.osName()).isEqualTo("iOS 18.1");
        assertThat(context.clientType()).isEqualTo(LoginClientType.MOBILE);
        assertThat(context.appVersion()).isEqualTo("1.4.0");
        assertThat(context.ipAddress()).isEqualTo("113.161.10.20");
    }

    @Test
    void webWithoutHeadersIsDerivedFromUserAgentAndStillRecorded() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", CHROME_WINDOWS);

        LoginDeviceContext context = LoginDeviceHeaders.from(request);

        assertThat(context.deviceName()).isEqualTo("Chrome trên Windows");
        assertThat(context.osName()).isEqualTo("Windows");
        assertThat(context.clientType()).isEqualTo(LoginClientType.WEB);
        assertThat(context.deviceId()).startsWith("ua-").hasSize(43);
        // Cùng trình duyệt thì cùng mã: lần đăng nhập sau cập nhật đúng dòng cũ.
        assertThat(LoginDeviceHeaders.from(request).deviceId()).isEqualTo(context.deviceId());
    }

    @Test
    void userAgentNamesCommonBrowsers() {
        assertThat(LoginDeviceHeaders.nameFromUserAgent(SAFARI_IPHONE, LoginClientType.WEB)).isEqualTo("Safari trên iOS");
        assertThat(LoginDeviceHeaders.osFromUserAgent(SAFARI_IPHONE)).isEqualTo("iOS 17.1");
        assertThat(LoginDeviceHeaders.nameFromUserAgent("okhttp/4.12.0", LoginClientType.MOBILE))
                .isEqualTo("Ứng dụng FINORA");
        assertThat(LoginDeviceHeaders.nameFromUserAgent(null, LoginClientType.WEB))
                .isEqualTo("Trình duyệt không xác định");
    }

    @Test
    void untrustedHeadersAreCleanedOrIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", CHROME_WINDOWS);
        request.addHeader(LoginDeviceHeaders.DEVICE_ID, "../../etc/passwd");
        request.addHeader(LoginDeviceHeaders.DEVICE_NAME, "Máy\u0000 của\n tôi " + "x".repeat(200));
        request.addHeader(LoginDeviceHeaders.APP_VERSION, "<script>");

        LoginDeviceContext context = LoginDeviceHeaders.from(request);

        assertThat(context.deviceId()).startsWith("ua-");
        assertThat(context.deviceName()).startsWith("Máy của tôi ").hasSize(LoginDeviceHeaders.MAX_NAME);
        assertThat(context.appVersion()).isNull();
    }

    @Test
    void ipIsMaskedForOwnerView() {
        assertThat(LoginDeviceHeaders.maskIp("113.161.10.20")).isEqualTo("113.161.*.*");
        assertThat(LoginDeviceHeaders.maskIp("2001:db8:85a3::8a2e")).isEqualTo("2001:db8:*");
        assertThat(LoginDeviceHeaders.maskIp(null)).isNull();
    }
}
