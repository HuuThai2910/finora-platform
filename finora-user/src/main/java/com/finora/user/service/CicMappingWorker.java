package com.finora.user.service;

/**
 * Worker định kỳ đăng ký ánh xạ người dùng đã xác minh sang CIC (bật bằng {@code finora.cic.mapping.enabled}).
 */
public interface CicMappingWorker {

    /** Một vòng: xử lý lần lượt các task đến hạn, task lỗi không chặn task sau. */
    void run();
}
