package com.finora.common.document.pades;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bản PDF đã thêm trường chữ ký ẩn bằng incremental update nhưng /Contents còn để trống (toàn ký tự '0').
 *
 * @param contentsOffset vị trí ký tự {@code <} của /Contents; ByteRange = [0, offset, offset + length, phần còn lại]
 * @param documentDigest SHA-256 của toàn bộ ByteRange, trở thành thuộc tính message-digest của CMS
 */
public record PreparedPadesRevision(
        byte[] content,
        String contentHash,
        int contentsOffset,
        int contentsLength,
        byte[] documentDigest,
        Instant signingTime
) {
    public PreparedPadesRevision {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(documentDigest, "documentDigest");
        Objects.requireNonNull(signingTime, "signingTime");
        content = Arrays.copyOf(content, content.length);
        documentDigest = Arrays.copyOf(documentDigest, documentDigest.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] documentDigest() {
        return Arrays.copyOf(documentDigest, documentDigest.length);
    }

    public String documentDigestHex() {
        return PadesDigests.hex(documentDigest);
    }
}
