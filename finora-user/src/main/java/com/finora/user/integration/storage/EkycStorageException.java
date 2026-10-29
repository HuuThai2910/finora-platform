package com.finora.user.integration.storage;

/**
 * Kho lưu bằng chứng không thực hiện được thao tác. Message không chứa nội dung file hay PII,
 * chỉ có tên thao tác và mã lỗi của kho.
 */
public class EkycStorageException extends RuntimeException {

    private final boolean notFound;

    public EkycStorageException(String message, boolean notFound, Throwable cause) {
        super(message, cause);
        this.notFound = notFound;
    }

    public EkycStorageException(String message) {
        this(message, false, null);
    }

    /** Object không tồn tại (đã bị xoá theo hạn lưu hoặc bởi vòng đời bucket). */
    public boolean isNotFound() {
        return notFound;
    }
}
