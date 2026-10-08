package com.finora.user.repository;

import com.finora.common.enums.user.EkycStatus;
import com.finora.common.enums.user.UserRole;
import com.finora.common.statistics.StatisticsPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Câu đếm theo cột thời gian trên {@code user_profiles} cho trang thống kê quản trị.
 * <p>
 * Dùng SQL thuần thay vì JPQL vì cần {@code date_trunc ... AT TIME ZONE} của PostgreSQL và chỉ đọc số
 * đếm, không nạp entity. Mỗi chỉ số một câu {@code GROUP BY}: số câu cố định, không phụ thuộc số cột.
 * <p>
 * Cột được cắt theo giờ Việt Nam (DB lưu UTC); điều kiện lọc đặt trên cột gốc chưa bọc hàm để còn dùng
 * được index {@code idx_user_profiles_created_at}.
 */
@Repository
@RequiredArgsConstructor
public class UserStatisticsRepository {

    private static final String REGISTRATIONS_SQL = """
            SELECT date_trunc(:unit, created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS bucket_start,
                   COUNT(*) AS registered,
                   COUNT(*) FILTER (WHERE role = :borrower) AS borrowers,
                   COUNT(*) FILTER (WHERE role = :investor) AS investors
            FROM user_profiles
            WHERE created_at >= :startInclusive AND created_at < :endExclusive
            GROUP BY 1
            """;

    // Trạng thái lấy theo giá trị hiện tại: hồ sơ VERIFIED rồi bị chuyển trạng thái khác sẽ không còn
    // được đếm ở cột cũ. Không có bảng lịch sử eKYC nên đây là cách đếm trung thực duy nhất.
    private static final String EKYC_SQL = """
            SELECT date_trunc(:unit, ekyc_completed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS bucket_start,
                   COUNT(*) FILTER (WHERE ekyc_status = :verified) AS verified,
                   COUNT(*) FILTER (WHERE ekyc_status = :failed) AS failed
            FROM user_profiles
            WHERE ekyc_completed_at >= :startInclusive AND ekyc_completed_at < :endExclusive
              AND ekyc_status IN (:verified, :failed)
            GROUP BY 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /** Số hồ sơ tạo mới mỗi cột, tách theo vai trò hiện tại. Chỉ trả cột có dữ liệu. */
    public List<RegistrationBucketRow> countRegistrations(StatisticsPeriod period) {
        MapSqlParameterSource params = periodParams(period)
                .addValue("borrower", UserRole.BORROWER.name())
                .addValue("investor", UserRole.INVESTOR.name());
        return jdbcTemplate.query(REGISTRATIONS_SQL, params, (rs, rowNum) -> new RegistrationBucketRow(
                rs.getObject("bucket_start", LocalDate.class),
                rs.getLong("registered"),
                rs.getLong("borrowers"),
                rs.getLong("investors")));
    }

    /** Số hồ sơ hoàn tất eKYC mỗi cột theo {@code ekyc_completed_at}, tách VERIFIED/FAILED. */
    public List<EkycBucketRow> countEkycCompletions(StatisticsPeriod period) {
        MapSqlParameterSource params = periodParams(period)
                .addValue("verified", EkycStatus.VERIFIED.name())
                .addValue("failed", EkycStatus.FAILED.name());
        return jdbcTemplate.query(EKYC_SQL, params, (rs, rowNum) -> new EkycBucketRow(
                rs.getObject("bucket_start", LocalDate.class),
                rs.getLong("verified"),
                rs.getLong("failed")));
    }

    /**
     * Đơn vị cột truyền dạng tham số (PostgreSQL nhận {@code text} cho {@code date_trunc}); mốc thời gian
     * truyền {@link OffsetDateTime} UTC vì driver PostgreSQL không bind trực tiếp {@code Instant}.
     */
    private static MapSqlParameterSource periodParams(StatisticsPeriod period) {
        return new MapSqlParameterSource()
                .addValue("unit", period.bucket().sqlUnit())
                .addValue("startInclusive", OffsetDateTime.ofInstant(period.startInclusive(), ZoneOffset.UTC))
                .addValue("endExclusive", OffsetDateTime.ofInstant(period.endExclusive(), ZoneOffset.UTC));
    }

    public record RegistrationBucketRow(LocalDate bucketStart, long registered, long borrowers, long investors) {
    }

    public record EkycBucketRow(LocalDate bucketStart, long verified, long failed) {
    }
}
