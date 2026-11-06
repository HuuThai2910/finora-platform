package com.finora.gateway.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Chép {@code limit} byte đầu của body trả về trong khi vẫn ghi thẳng ra client. Khác
 * {@code ContentCachingResponseWrapper}: không giữ cả body lại tới cuối request, nên tải tệp lớn hay luồng dài vẫn đi
 * qua như cũ.
 */
class CapturingResponseWrapper extends HttpServletResponseWrapper {

    private final int limit;
    private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
    private long written;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    CapturingResponseWrapper(HttpServletResponse response, int limit) {
        super(response);
        this.limit = limit;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (outputStream == null) {
            outputStream = new TeeOutputStream(super.getOutputStream());
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset), false);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) writer.flush();
        super.flushBuffer();
    }

    /**
     * Body đã chép, giải mã theo {@code charset} khai báo trong Content-Type; không khai báo thì UTF-8 (JSON luôn là
     * UTF-8, còn mặc định ISO-8859-1 của Servlet làm hỏng tiếng Việt trong câu lỗi).
     */
    String capturedText() {
        if (writer != null) writer.flush();
        if (copy.size() == 0) return null;
        return copy.toString(declaredCharset());
    }

    private Charset declaredCharset() {
        try {
            MediaType type = getContentType() == null ? null : MediaType.parseMediaType(getContentType());
            return type != null && type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
        } catch (RuntimeException invalid) {
            return StandardCharsets.UTF_8;
        }
    }

    boolean isTruncated() {
        return written > limit;
    }

    private void record(int b) {
        written++;
        if (copy.size() < limit) copy.write(b);
    }

    private void record(byte[] bytes, int offset, int length) {
        written += length;
        int room = limit - copy.size();
        if (room > 0) copy.write(bytes, offset, Math.min(room, length));
    }

    private final class TeeOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;

        TeeOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            record(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            record(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }
}
