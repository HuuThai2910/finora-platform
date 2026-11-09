package com.finora.user.util;

import com.finora.common.util.Sha256;
import com.finora.user.domain.enums.LoginClientType;
import com.finora.user.domain.model.LoginDeviceContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc thiết bị của lượt đăng nhập từ header ({@code X-Device-Id}, {@code X-Device-Name}, {@code X-Device-Os},
 * {@code X-App-Version}); thiếu header thì suy từ {@code User-Agent} để vẫn ghi được (web cũ, client chưa cập nhật).
 * <p>
 * Header là dữ liệu client tự khai: bỏ ký tự điều khiển, cắt độ dài, mã thiết bị chỉ nhận chữ/số/gạch. Chỉ để hiển thị.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LoginDeviceHeaders {

    public static final String DEVICE_ID = "X-Device-Id";
    public static final String DEVICE_NAME = "X-Device-Name";
    public static final String DEVICE_OS = "X-Device-Os";
    public static final String APP_VERSION = "X-App-Version";

    static final int MAX_NAME = 100;
    static final int MAX_OS = 60;
    static final int MAX_IP = 45;

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern APP_VERSION_PATTERN = Pattern.compile("[0-9A-Za-z.+-]{1,30}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}]");
    private static final Pattern ANDROID = Pattern.compile("Android (\\d+(?:\\.\\d+)?)");
    private static final Pattern IOS = Pattern.compile("(?:iPhone|CPU) OS (\\d+)(?:_(\\d+))?");
    private static final Pattern MAC = Pattern.compile("Mac OS X (\\d+)[_.](\\d+)");

    /** Thiết bị của request; không bao giờ ném lỗi vì header xấu (bỏ header đó và suy từ User-Agent). */
    public static LoginDeviceContext from(HttpServletRequest request) {
        String userAgent = HttpRequestUtils.extractUserAgent(request);
        LoginClientType clientType = HttpRequestUtils.isMobileClient(request) ? LoginClientType.MOBILE : LoginClientType.WEB;
        String os = text(request.getHeader(DEVICE_OS), MAX_OS);
        if (os == null) {
            os = osFromUserAgent(userAgent);
        }
        String name = text(request.getHeader(DEVICE_NAME), MAX_NAME);
        if (name == null) {
            name = nameFromUserAgent(userAgent, clientType);
        }
        return new LoginDeviceContext(
                deviceId(request.getHeader(DEVICE_ID), userAgent),
                name,
                os,
                clientType,
                appVersion(request.getHeader(APP_VERSION)),
                truncate(HttpRequestUtils.extractIpAddress(request), MAX_IP));
    }

    /**
     * Mã thiết bị hợp lệ thì dùng; thiếu hoặc sai dạng thì băm User-Agent để cùng trình duyệt gộp về một dòng (hai máy
     * cùng User-Agent sẽ chung dòng, chấp nhận được vì chỉ để hiển thị).
     */
    static String deviceId(String header, String userAgent) {
        if (header != null && DEVICE_ID_PATTERN.matcher(header.trim()).matches()) {
            return header.trim();
        }
        String source = userAgent == null || userAgent.isBlank() ? "unknown" : userAgent.trim();
        return "ua-" + Sha256.hex(source).substring(0, 40);
    }

    static String appVersion(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        return APP_VERSION_PATTERN.matcher(value).matches() ? value : null;
    }

    /** Tên hiển thị suy từ User-Agent: "Chrome trên Windows", "Safari trên iOS 17.1"... */
    static String nameFromUserAgent(String userAgent, LoginClientType clientType) {
        if (userAgent == null || userAgent.isBlank()) {
            return clientType == LoginClientType.MOBILE ? "Ứng dụng FINORA" : "Trình duyệt không xác định";
        }
        String browser = browser(userAgent);
        String os = osFromUserAgent(userAgent);
        if (browser == null) {
            // App di động gọi bằng thư viện HTTP (okhttp, CFNetwork...) không có tên trình duyệt.
            return clientType == LoginClientType.MOBILE || os == null ? "Ứng dụng FINORA" : "Trình duyệt trên " + os;
        }
        return os == null ? browser : browser + " trên " + osFamily(os);
    }

    /** Hệ điều hành và phiên bản suy từ User-Agent; {@code null} khi không nhận ra. */
    static String osFromUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        Matcher android = ANDROID.matcher(userAgent);
        if (android.find()) {
            return "Android " + android.group(1);
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            Matcher ios = IOS.matcher(userAgent);
            return ios.find() ? "iOS " + ios.group(1) + (ios.group(2) == null ? "" : "." + ios.group(2)) : "iOS";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        Matcher mac = MAC.matcher(userAgent);
        if (mac.find()) {
            return "macOS " + mac.group(1) + "." + mac.group(2);
        }
        if (userAgent.contains("Macintosh")) {
            return "macOS";
        }
        if (userAgent.contains("CrOS")) {
            return "ChromeOS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return null;
    }

    private static String browser(String userAgent) {
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("OPR/")) {
            return "Opera";
        }
        if (userAgent.contains("CocCoc")) {
            return "Cốc Cốc";
        }
        if (userAgent.contains("Firefox/")) {
            return "Firefox";
        }
        if (userAgent.contains("Chrome/") || userAgent.contains("CriOS/")) {
            return "Chrome";
        }
        if (userAgent.contains("Safari/") && userAgent.contains("Version/")) {
            return "Safari";
        }
        return null;
    }

    private static String osFamily(String os) {
        int space = os.indexOf(' ');
        String family = space < 0 ? os : os.substring(0, space);
        return family.toLowerCase(Locale.ROOT).equals("macos") ? "macOS" : family;
    }

    /**
     * IP che cho chính người dùng xem: IPv4 giữ hai nhóm đầu ({@code 113.161.*.*}), IPv6 giữ hai nhóm đầu. Đủ để nhận ra
     * nhà mạng/khu vực mà không lộ địa chỉ đầy đủ khi chụp màn hình.
     */
    public static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String value = ip.trim();
        if (value.contains(":")) {
            String[] groups = value.split(":");
            return groups.length < 2 ? "*" : groups[0] + ":" + groups[1] + ":*";
        }
        String[] parts = value.split("\\.");
        return parts.length == 4 ? parts[0] + "." + parts[1] + ".*.*" : "*";
    }

    /** Bỏ ký tự điều khiển, gộp khoảng trắng, cắt độ dài; rỗng thì {@code null}. */
    static String text(String header, int maxLength) {
        if (header == null) {
            return null;
        }
        String value = CONTROL.matcher(header).replaceAll("").replaceAll("\\s+", " ").trim();
        return value.isEmpty() ? null : truncate(value, maxLength);
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
