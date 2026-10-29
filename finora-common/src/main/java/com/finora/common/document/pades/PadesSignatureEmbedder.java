package com.finora.common.document.pades;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Ghi CMS (hex) vào chỗ trống /Contents của bản đã chuẩn bị. Chỉ các ký tự '0' bên trong {@code <...>} bị thay;
 * độ dài tệp và mọi byte trong ByteRange giữ nguyên, nên digest đã gửi ký vẫn đúng với tệp sau khi nhúng.
 */
public class PadesSignatureEmbedder {

    public byte[] embed(byte[] prepared, int contentsOffset, int contentsLength, byte[] cms) {
        if (prepared == null || cms == null || cms.length == 0) {
            throw new IllegalArgumentException("Thiếu PDF đã chuẩn bị hoặc CMS");
        }
        int hexStart = contentsOffset + 1;
        int hexEnd = contentsOffset + contentsLength - 1;
        if (contentsOffset <= 0 || hexEnd >= prepared.length
                || prepared[contentsOffset] != '<' || prepared[hexEnd] != '>') {
            throw new PadesSignatureException("PADES_PLACEHOLDER_INVALID", "Không tìm thấy chỗ trống /Contents");
        }
        for (int index = hexStart; index < hexEnd; index++) {
            if (prepared[index] != '0') {
                throw new PadesSignatureException(
                        "PADES_ALREADY_EMBEDDED", "Chỗ trống /Contents đã có chữ ký; không ghi đè chữ ký cũ");
            }
        }
        byte[] hex = HexFormat.of().withUpperCase().formatHex(cms).getBytes(StandardCharsets.US_ASCII);
        if (hex.length > hexEnd - hexStart) {
            throw new PadesSignatureException(
                    "PADES_SIGNATURE_TOO_LARGE", "CMS lớn hơn chỗ trống đã dành trong PDF");
        }
        byte[] signed = Arrays.copyOf(prepared, prepared.length);
        System.arraycopy(hex, 0, signed, hexStart, hex.length);
        return signed;
    }
}
