package com.finora.blockchain.service.proof;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofHashScheme;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tính hash của artifact nguồn theo {@link ProofHashScheme}. Artifact là object {@code data} của event nghiệp vụ, tức
 * dữ liệu service nguồn đã commit và còn giữ trong outbox; người kiểm toán tính lại được bằng cùng quy tắc, ví dụ
 * {@code jq -cSj 'walk(if type=="object" then with_entries(select(.value!=null)) else . end)'} rồi {@code sha256sum}
 * cho dữ liệu không có ký tự điều khiển.
 */
public final class ProofPayloadHasher {

    /** Số nguyên an toàn của RFC 8785/I-JSON; ngoài khoảng này hai cách parse có thể ra số khác nhau. */
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);

    private ProofPayloadHasher() {
    }

    public static String hash(JsonNode artifact, ProofHashScheme scheme, ObjectMapper mapper) {
        return sha256Hex(bytes(artifact, scheme, mapper));
    }

    public static byte[] bytes(JsonNode artifact, ProofHashScheme scheme, ObjectMapper mapper) {
        if (artifact == null || !artifact.isObject()) {
            throw new IllegalArgumentException("Artifact proof phải là JSON object (field data của event)");
        }
        return switch (scheme) {
            case LEGACY_COMPACT_V1 -> legacyCompact(artifact, mapper);
            case CANONICAL_JSON_V2 -> canonical(artifact).getBytes(StandardCharsets.UTF_8);
        };
    }

    /** FINORA canonical JSON v2: tập con RFC 8785 (JCS) sau khi bỏ member null; xem {@link ProofHashScheme}. */
    public static String canonical(JsonNode artifact) {
        StringBuilder output = new StringBuilder(256);
        write(artifact, output);
        return output.toString();
    }

    public static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Runtime thiếu SHA-256", exception);
        }
    }

    private static byte[] legacyCompact(JsonNode artifact, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsBytes(artifact);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Không ghi được artifact dạng JSON compact", exception);
        }
    }

    private static void write(JsonNode node, StringBuilder output) {
        switch (node.getNodeType()) {
            case OBJECT -> writeObject(node, output);
            case ARRAY -> writeArray(node, output);
            case STRING -> writeString(node.textValue(), output);
            case BOOLEAN -> output.append(node.booleanValue() ? "true" : "false");
            case NULL -> output.append("null");
            case NUMBER -> writeNumber(node, output);
            default -> throw new IllegalArgumentException("Kiểu JSON không hỗ trợ trong artifact: " + node.getNodeType());
        }
    }

    private static void writeObject(JsonNode node, StringBuilder output) {
        List<Map.Entry<String, JsonNode>> members = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> member = fields.next();
            // null và vắng mặt cùng nghĩa "không cung cấp" trong event FINORA; bỏ để serializer NON_NULL không đổi hash.
            if (!member.getValue().isNull()) {
                members.add(member);
            }
        }
        // String.compareTo so theo UTF-16 code unit, đúng thứ tự RFC 8785 yêu cầu.
        members.sort(Map.Entry.comparingByKey());
        output.append('{');
        for (int index = 0; index < members.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            writeString(members.get(index).getKey(), output);
            output.append(':');
            write(members.get(index).getValue(), output);
        }
        output.append('}');
    }

    private static void writeArray(JsonNode node, StringBuilder output) {
        output.append('[');
        for (int index = 0; index < node.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            write(node.get(index), output);
        }
        output.append(']');
    }

    private static void writeNumber(JsonNode node, StringBuilder output) {
        // Tiền trong event FINORA luôn là chuỗi decimal; số thực xuất hiện nghĩa là sai contract nên không neo.
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("Artifact proof chỉ nhận số nguyên; tiền phải là chuỗi decimal");
        }
        BigInteger value = node.bigIntegerValue();
        if (value.abs().compareTo(MAX_SAFE_INTEGER) > 0) {
            throw new IllegalArgumentException("Số nguyên vượt khoảng an toàn ±(2^53-1) của canonical JSON");
        }
        output.append(value);
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
