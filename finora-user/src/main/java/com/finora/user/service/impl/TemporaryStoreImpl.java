package com.finora.user.service.impl;

import com.finora.user.service.TemporaryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Kho trạng thái tạm có thời hạn của finora-user trên PostgreSQL riêng của service (thay Redis).
 * <p>
 * Thời điểm "bây giờ" lấy từ {@link Clock} của service và truyền vào câu SQL, không dùng {@code now()} của
 * database, để test điều khiển được thời gian và mọi instance cùng một mốc với phần còn lại của service.
 * Thao tác ghi đều là một câu lệnh duy nhất ({@code INSERT ... ON CONFLICT}, {@code DELETE ... RETURNING})
 * nên nguyên tử như lệnh Redis tương ứng mà không cần khoá tường minh.
 * <p>
 * Mọi method chạy {@code REQUIRES_NEW}: nơi gọi như {@code AuthServiceImpl} có {@code @Transactional} cấp
 * class, nhiều method {@code readOnly = true} (PostgreSQL từ chối ghi), và đăng nhập sai ném lỗi sau khi đếm
 * (rollback sẽ xoá lần đếm, mở đường dò mật khẩu); dọn phiên eKYC lại chạy sau commit. Transaction riêng giữ
 * đúng hành vi của Redis: ghi xong là có hiệu lực. Đổi lại mỗi lời gọi mượn thêm một kết nối trong pool khi
 * nơi gọi đang giữ transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class TemporaryStoreImpl implements TemporaryStore {

    private static final String SQL_PUT = """
            INSERT INTO user_temporary_entries (entry_key, entry_value, expires_at)
            VALUES (:key, :value, :expiresAt)
            ON CONFLICT (entry_key) DO UPDATE
                SET entry_value = EXCLUDED.entry_value,
                    expires_at = EXCLUDED.expires_at
            """;

    /** Chỉ ghi đè khi bản đang có đã hết hạn; bản còn hạn giữ nguyên và câu lệnh trả 0 dòng. */
    private static final String SQL_PUT_IF_ABSENT = """
            INSERT INTO user_temporary_entries (entry_key, entry_value, expires_at)
            VALUES (:key, :value, :expiresAt)
            ON CONFLICT (entry_key) DO UPDATE
                SET entry_value = EXCLUDED.entry_value,
                    expires_at = EXCLUDED.expires_at
                WHERE user_temporary_entries.expires_at <= :now
            """;

    private static final String SQL_FIND = """
            SELECT entry_value FROM user_temporary_entries
            WHERE entry_key = :key AND expires_at > :now
            """;

    private static final String SQL_EXISTS = """
            SELECT EXISTS (
                SELECT 1 FROM user_temporary_entries
                WHERE entry_key = :key AND expires_at > :now)
            """;

    /** Xoá cả bản đã hết hạn (dọn sớm), nhưng chỉ trả giá trị nếu còn hạn. */
    private static final String SQL_TAKE = """
            DELETE FROM user_temporary_entries
            WHERE entry_key = :key
            RETURNING entry_value, expires_at > :now AS is_live
            """;

    private static final String SQL_DELETE = "DELETE FROM user_temporary_entries WHERE entry_key = :key";

    /**
     * Bộ đếm hết hạn coi như không tồn tại: tính lại từ 1 và mở cửa sổ mới. Còn hạn thì cộng 1, giữ nguyên
     * thời hạn. Hai biểu thức {@code CASE} đều đọc giá trị cũ của dòng.
     */
    private static final String SQL_INCREMENT_WITHIN_WINDOW = """
            INSERT INTO user_rate_counters (counter_key, counter_value, expires_at)
            VALUES (:key, 1, :expiresAt)
            ON CONFLICT (counter_key) DO UPDATE
                SET counter_value = CASE WHEN user_rate_counters.expires_at <= :now THEN 1
                                         ELSE user_rate_counters.counter_value + 1 END,
                    expires_at = CASE WHEN user_rate_counters.expires_at <= :now THEN EXCLUDED.expires_at
                                      ELSE user_rate_counters.expires_at END
            RETURNING counter_value
            """;

    private static final String SQL_INCREMENT_REFRESHING_TTL = """
            INSERT INTO user_rate_counters (counter_key, counter_value, expires_at)
            VALUES (:key, 1, :expiresAt)
            ON CONFLICT (counter_key) DO UPDATE
                SET counter_value = CASE WHEN user_rate_counters.expires_at <= :now THEN 1
                                         ELSE user_rate_counters.counter_value + 1 END,
                    expires_at = EXCLUDED.expires_at
            RETURNING counter_value
            """;

    private static final String SQL_FIND_COUNTER = """
            SELECT counter_value FROM user_rate_counters
            WHERE counter_key = :key AND expires_at > :now
            """;

    private static final String SQL_DELETE_COUNTER = "DELETE FROM user_rate_counters WHERE counter_key = :key";

    private static final String SQL_DELETE_EXPIRED_ENTRIES =
            "DELETE FROM user_temporary_entries WHERE expires_at <= :now";

    private static final String SQL_DELETE_EXPIRED_COUNTERS =
            "DELETE FROM user_rate_counters WHERE expires_at <= :now";

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final Clock clock;

    @Override
    public void put(String key, String value, Duration ttl) {
        namedParameterJdbcTemplate.update(SQL_PUT, entryParams(key, value, ttl));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<String> find(String key) {
        List<String> values = namedParameterJdbcTemplate.queryForList(SQL_FIND, keyParams(key), String.class);
        return values.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean exists(String key) {
        return Boolean.TRUE.equals(namedParameterJdbcTemplate.queryForObject(SQL_EXISTS, keyParams(key), Boolean.class));
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        return namedParameterJdbcTemplate.update(SQL_PUT_IF_ABSENT, entryParams(key, value, ttl)) > 0;
    }

    @Override
    public Optional<String> take(String key) {
        List<Optional<String>> rows = namedParameterJdbcTemplate.query(SQL_TAKE, keyParams(key),
                (rs, rowNum) -> rs.getBoolean("is_live")
                        ? Optional.of(rs.getString("entry_value"))
                        : Optional.<String>empty());
        return rows.isEmpty() ? Optional.empty() : rows.get(0);
    }

    @Override
    public void delete(String key) {
        namedParameterJdbcTemplate.update(SQL_DELETE, new MapSqlParameterSource("key", key));
    }

    @Override
    public long incrementWithinWindow(String key, Duration window) {
        return increment(SQL_INCREMENT_WITHIN_WINDOW, key, window);
    }

    @Override
    public long incrementRefreshingTtl(String key, Duration ttl) {
        return increment(SQL_INCREMENT_REFRESHING_TTL, key, ttl);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long findCounter(String key) {
        List<Long> values = namedParameterJdbcTemplate.queryForList(SQL_FIND_COUNTER, keyParams(key), Long.class);
        return values.isEmpty() ? 0L : values.get(0);
    }

    @Override
    public void deleteCounter(String key) {
        namedParameterJdbcTemplate.update(SQL_DELETE_COUNTER, new MapSqlParameterSource("key", key));
    }

    @Override
    public int deleteExpired() {
        MapSqlParameterSource params = new MapSqlParameterSource("now", now());
        return namedParameterJdbcTemplate.update(SQL_DELETE_EXPIRED_ENTRIES, params)
                + namedParameterJdbcTemplate.update(SQL_DELETE_EXPIRED_COUNTERS, params);
    }

    private long increment(String sql, String key, Duration ttl) {
        OffsetDateTime now = now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("now", now)
                .addValue("expiresAt", now.plus(ttl));
        Long count = namedParameterJdbcTemplate.queryForObject(sql, params, Long.class);
        if (count == null) {
            throw new IllegalStateException("Câu lệnh tăng bộ đếm không trả về giá trị: " + key);
        }
        return count;
    }

    private MapSqlParameterSource entryParams(String key, String value, Duration ttl) {
        OffsetDateTime now = now();
        return new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("value", value)
                .addValue("now", now)
                .addValue("expiresAt", now.plus(ttl));
    }

    private MapSqlParameterSource keyParams(String key) {
        return new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("now", now());
    }

    /** Mốc thời gian UTC; pgjdbc ánh xạ {@link OffsetDateTime} sang {@code timestamptz}. */
    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
