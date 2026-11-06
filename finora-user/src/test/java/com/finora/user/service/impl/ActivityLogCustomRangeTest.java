package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.user.dto.response.ActivityLogEntryResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Đợt 2 nhật ký: khoảng tự chọn, ẩn lượt gọi tự động, xuất CSV. */
class ActivityLogCustomRangeTest {

    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-03T17:00:00Z");

    @Test
    void customRangeUsesBetweenAndHidesBackgroundByDefault() {
        String kql = ActivityLogKql.rows(new ActivityLogKql.Filters("24h", null, null, null, null, null,
                FROM, TO, false), 50);

        assertThat(kql).contains("TimeGenerated between (datetime(2026-11-01T00:00:00Z) .. datetime(2026-11-03T17:00:00Z))")
                .doesNotContain("ago(24h)")
                .contains("isnull(background) or not(background)");
    }

    @Test
    void includeBackgroundKeepsAutomaticCalls() {
        String kql = ActivityLogKql.rows(new ActivityLogKql.Filters("7d", null, null, null, null, null,
                null, null, true), 50);

        assertThat(kql).contains("ago(7d)").doesNotContain("isnull(background)");
    }

    @Test
    void rangeLongerThan31DaysIsRejected() {
        assertThatThrownBy(() -> ActivityLogKql.rows(new ActivityLogKql.Filters(null, null, null, null, null, null,
                FROM, FROM.plusSeconds(32L * 24 * 3600), false), 50))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ActivityLogKql.rows(new ActivityLogKql.Filters(null, null, null, null, null, null,
                TO, FROM, false), 50))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void csvHasBomQuotesAndNeutralisesFormulas() {
        String csv = ActivityLogCsv.write(List.of(ActivityLogEntryResponse.builder()
                .at(Instant.parse("2026-11-04T02:30:00Z")).method("POST").path("/api/v1/x")
                .status(400).durationMs(12).user("a@b.vn").errorMessage("Sai, \"lỗi\"").errorCode("=CMD()").build()));

        assertThat(csv).startsWith("\uFEFFThời điểm,");
        assertThat(csv).contains("2026-11-04 09:30:00,POST,/api/v1/x");
        assertThat(csv).contains("'=CMD()");
        assertThat(csv).contains("\"Sai, \"\"lỗi\"\"\"");
    }
}
