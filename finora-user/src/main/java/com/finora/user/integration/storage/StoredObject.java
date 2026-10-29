package com.finora.user.integration.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Luồng đọc một object bằng chứng. Người gọi phải đóng để trả kết nối HTTP về pool.
 *
 * @param content       nội dung object
 * @param contentLength số byte, {@code -1} nếu kho không trả
 * @param contentType   kiểu nội dung kho trả về, có thể {@code null}
 */
public record StoredObject(InputStream content, long contentLength, String contentType) implements AutoCloseable {

    @Override
    public void close() {
        try {
            content.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
