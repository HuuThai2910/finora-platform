package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivityLogKqlTest {

    private static ActivityLogKql.Filters filters(String method, String outcome, String service, String client, String q) {
        return new ActivityLogKql.Filters("24h", method, outcome, service, client, q);
    }

    @Test
    void buildsWhereFromAllowedValues() {
        String condition = ActivityLogKql.condition(filters("write", "CLIENT_ERROR", "loan-service", "mobile", null));

        assertThat(condition).isEqualTo("method !in (\"GET\", \"HEAD\") and status >= 400 and status < 500"
                + " and service == \"loan-service\" and client == \"MOBILE\"");
    }

    @Test
    void escapesFreeTextSoItCannotBreakOutOfTheString() {
        String condition = ActivityLogKql.condition(filters(null, null, null, null, "x\" or 1==1 | take 1 //\\"));

        assertThat(condition).contains("path contains \"x\\\" or 1==1 | take 1 //\\\\\"");
        assertThat(ActivityLogKql.literal("a\nb")).isEqualTo("\"ab\"");
        assertThat(ActivityLogKql.literal("y".repeat(300))).hasSize(102);
    }

    @Test
    void rejectsValuesOutsideTheAllowList() {
        assertThatThrownBy(() -> ActivityLogKql.condition(filters("TRUNCATE", null, null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ActivityLogKql.condition(filters(null, null, "loan\" or true", null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ActivityLogKql.rows(new ActivityLogKql.Filters("2y", null, null, null, null, null), 50))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rowsQueryOrdersNewestFirstAndSkipsItsOwnCalls() {
        String kql = ActivityLogKql.rows(new ActivityLogKql.Filters("7d", "GET", null, null, null, null), 100);

        assertThat(kql).contains("ago(7d)").contains("not(path startswith \"/api/v1/admin/activity-logs\")")
                .contains("| where method == \"GET\"").endsWith("| order by at desc\n| take 100");
        assertThat(ActivityLogKql.stats(new ActivityLogKql.Filters(null, null, null, null, null, null)))
                .contains("ago(24h)").contains("matched = countif(true)");
    }
}
