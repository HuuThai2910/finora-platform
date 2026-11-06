package com.finora.user.service;

import java.time.Duration;
import java.util.Optional;

/**
 * Kho trạng thái tạm có thời hạn của finora-user trên PostgreSQL riêng của service (thay Redis).
 * <p>
 * Hai loại dữ liệu: giá trị chuỗi ({@code user_temporary_entries}) và bộ đếm ({@code user_rate_counters}).
 * Bản ghi đã quá {@code expires_at} được coi như không tồn tại với mọi thao tác, kể cả khi worker dọn chưa
 * kịp xoá. Mỗi thao tác chạy trong transaction riêng và có hiệu lực ngay, độc lập với transaction của nơi
 * gọi (như Redis trước đây): nơi gọi rollback không làm mất lần đăng nhập sai đã đếm, method
 * {@code readOnly} vẫn ghi được, và lời gọi sau commit vẫn được lưu.
 */
public interface TemporaryStore {

    /** Ghi đè giá trị và đặt lại thời hạn ({@code SET key value EX ttl}). */
    void put(String key, String value, Duration ttl);

    /** Đọc giá trị còn hạn ({@code GET}); rỗng nếu chưa có hoặc đã hết hạn. */
    Optional<String> find(String key);

    /** Có giá trị còn hạn hay không ({@code EXISTS}). */
    boolean exists(String key);

    /**
     * Ghi giá trị chỉ khi chưa có bản còn hạn ({@code SET key value NX EX ttl}); nguyên tử trong một câu lệnh
     * nên hai request đồng thời chỉ một request ghi được.
     *
     * @return {@code true} nếu đã ghi
     */
    boolean putIfAbsent(String key, String value, Duration ttl);

    /** Lấy và xoá giá trị trong một câu lệnh ({@code GETDEL}); rỗng nếu không có hoặc đã hết hạn. */
    Optional<String> take(String key);

    /** Xoá giá trị ({@code DEL}); không có thì bỏ qua. */
    void delete(String key);

    /**
     * Tăng bộ đếm trong cửa sổ cố định ({@code INCR} + {@code EXPIRE} khi kết quả là 1): thời hạn chỉ đặt ở lần
     * tăng đầu tiên của cửa sổ, các lần sau không kéo dài. Bộ đếm đã hết hạn được tính lại từ 1.
     *
     * @return giá trị sau khi tăng
     */
    long incrementWithinWindow(String key, Duration window);

    /**
     * Tăng bộ đếm và làm mới thời hạn mỗi lần ({@code INCR} + {@code EXPIRE} mọi lần). Bộ đếm đã hết hạn được
     * tính lại từ 1.
     *
     * @return giá trị sau khi tăng
     */
    long incrementRefreshingTtl(String key, Duration ttl);

    /** Giá trị bộ đếm còn hạn; {@code 0} nếu chưa có hoặc đã hết hạn. */
    long findCounter(String key);

    /** Xoá bộ đếm; không có thì bỏ qua. */
    void deleteCounter(String key);

    /**
     * Xoá hẳn mọi giá trị và bộ đếm đã hết hạn.
     *
     * @return tổng số dòng đã xoá ở cả hai bảng
     */
    int deleteExpired();
}
