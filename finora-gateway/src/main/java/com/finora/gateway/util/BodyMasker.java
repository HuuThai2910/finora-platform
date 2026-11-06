package com.finora.gateway.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Che dữ liệu nhạy cảm trước khi đưa body/query vào access log: mật khẩu, PIN, OTP, token, ảnh/video (CCCD, khuôn mặt,
 * chữ ký). Chuỗi quá dài (thường là base64 của ảnh) bị rút gọn kể cả khi tên trường không nằm trong danh sách.
 */
@Component
@RequiredArgsConstructor
public class BodyMasker {

    static final String MASK = "***";
    private static final int MAX_STRING = 200;
    private static final int MAX_ARRAY = 20;

    /** Khớp khi tên trường chứa chuỗi này ở bất kỳ đâu ({@code newPassword}, {@code pinToken}). */
    private static final List<String> SENSITIVE_PARTS = List.of("password", "passwd", "token", "secret", "otp");
    /** Khớp khi là một từ trong tên trường theo camelCase/snake_case ({@code currentPin}, {@code front_image}). */
    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "pin", "pwd", "authorization", "cookie", "cvv", "credential", "credentials", "signature",
            "image", "images", "photo", "selfie", "base64", "frame", "frames", "video", "face", "apikey");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");
    /** Dự phòng khi body không phải JSON hợp lệ (bị cắt ở giới hạn byte): che giá trị chuỗi của trường nhạy cảm. */
    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"([^\"\\\\]{1,64})\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"?");

    private final ObjectMapper objectMapper;

    public static boolean isSensitive(String key) {
        if (key == null || key.isBlank()) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        if (SENSITIVE_PARTS.stream().anyMatch(lower::contains)) return true;
        for (String word : WORD_BOUNDARY.split(key)) {
            if (SENSITIVE_WORDS.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Body đã che và cắt còn {@code maxChars} ký tự. {@code truncated} cho biết body gốc dài hơn giới hạn byte đã đọc,
     * lúc đó JSON thường hỏng ở cuối nên dùng cách che theo mẫu chuỗi.
     */
    public String mask(String body, String contentType, boolean truncated, int maxChars) {
        if (body == null || body.isEmpty()) return null;
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String masked;
        if (type.contains("x-www-form-urlencoded")) {
            masked = maskQuery(body);
        } else if (type.contains("json") || looksLikeJson(body)) {
            masked = maskJson(body);
        } else {
            masked = body;
        }
        if (truncated) masked += " …(đã cắt)";
        return cut(masked, maxChars);
    }

    /** Query string hoặc form: che giá trị của tham số nhạy cảm, giữ nguyên thứ tự. */
    public String maskQuery(String query) {
        if (query == null || query.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (String pair : query.split("&")) {
            int separatorIndex = pair.indexOf('=');
            String name = separatorIndex < 0 ? pair : pair.substring(0, separatorIndex);
            String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
            parts.add(separatorIndex >= 0 && isSensitive(decoded) ? name + "=" + MASK : pair);
        }
        return String.join("&", parts);
    }

    private String maskJson(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null) return body;
            return objectMapper.writeValueAsString(maskNode(root));
        } catch (Exception notJson) {
            return maskJsonText(body);
        }
    }

    private JsonNode maskNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<String> sensitive = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey()) && !field.getValue().isNull()) {
                    sensitive.add(field.getKey());
                } else {
                    field.setValue(maskNode(field.getValue()));
                }
            }
            sensitive.forEach(key -> object.put(key, MASK));
            return object;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode masked = objectMapper.createArrayNode();
            for (int i = 0; i < array.size() && i < MAX_ARRAY; i++) {
                masked.add(maskNode(array.get(i)));
            }
            if (array.size() > MAX_ARRAY) masked.add("…(" + (array.size() - MAX_ARRAY) + " phần tử nữa)");
            return masked;
        }
        if (node.isTextual() && node.textValue().length() > MAX_STRING) {
            return objectMapper.getNodeFactory().textNode(shorten(node.textValue()));
        }
        return node;
    }

    private static String maskJsonText(String body) {
        Matcher matcher = JSON_STRING_FIELD.matcher(body);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            String replacement = isSensitive(key) ? MASK : value.length() > MAX_STRING ? shorten(value) : value;
            matcher.appendReplacement(out, Matcher.quoteReplacement("\"" + key + "\":\"" + replacement + "\""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean looksLikeJson(String body) {
        String trimmed = body.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String shorten(String value) {
        return value.substring(0, 40) + "…(" + value.length() + " ký tự)";
    }

    /** Cắt chuỗi còn {@code maxChars} ký tự, thêm đuôi " …(đã cắt)" khi bị cắt. */
    public static String cut(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + " …(đã cắt)";
    }
}
