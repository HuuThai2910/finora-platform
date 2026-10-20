package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.integration.fineract.contract.FineractJob;
import com.finora.loan.integration.fineract.contract.FineractJobRun;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Adapter đọc job lập lịch của Fineract 1.15 (W3E, D57).
 *
 * <p>Không đi qua circuit breaker của {@link FineractRequestExecutor}: đây là kiểm tra vận hành vài phút một lần trên
 * luồng riêng, lỗi của nó không được làm mở circuit của servicing/booking (luồng tiền). Vẫn dùng chung xác thực và phân
 * loại lỗi để mã lỗi giống các adapter khác. Thời gian chờ bị chặn bởi connect/read timeout của {@code fineractRestClient}.</p>
 */
@Component
public class FineractJobHttpClient implements FineractJobGateway {

    private final RestClient client;
    private final FineractRequestExecutor executor;

    public FineractJobHttpClient(RestClient fineractRestClient, FineractRequestExecutor executor) {
        this.client = fineractRestClient;
        this.executor = executor;
    }

    @Override
    public List<FineractJob> listJobs() {
        JsonNode jobs = call(() -> client.get().uri("/jobs")
                .headers(executor::authenticate).retrieve().body(JsonNode.class));
        if (jobs == null || !jobs.isArray()) {
            throw invalid("Fineract không trả danh sách job");
        }
        List<FineractJob> result = new ArrayList<>(jobs.size());
        for (JsonNode job : jobs) {
            if (!job.path("jobId").canConvertToLong()) {
                continue;
            }
            result.add(new FineractJob(job.path("jobId").asLong(), text(job, "shortName"), text(job, "displayName"),
                    job.path("active").asBoolean(false), job.path("currentlyRunning").asBoolean(false),
                    run(job.path("lastRunHistory"))));
        }
        return result;
    }

    @Override
    public List<FineractJobRun> recentRuns(long jobId, int limit) {
        // orderBy=id DESC: không có ORDER BY thì PostgreSQL trả theo thứ tự tùy ý, trang đầu có thể chỉ gồm lần chạy cũ.
        JsonNode page = call(() -> client.get()
                .uri(builder -> builder.path("/jobs/{jobId}/runhistory")
                        .queryParam("offset", 0)
                        .queryParam("limit", limit)
                        .queryParam("orderBy", "id")
                        .queryParam("sortOrder", "DESC")
                        .build(jobId))
                .headers(executor::authenticate).retrieve().body(JsonNode.class));
        if (page == null || !page.path("pageItems").isArray()) {
            throw invalid("Fineract không trả lịch sử chạy job");
        }
        List<FineractJobRun> runs = new ArrayList<>();
        for (JsonNode item : page.path("pageItems")) {
            FineractJobRun run = run(item);
            if (run != null) {
                runs.add(run);
            }
        }
        return runs;
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (FineractIntegrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw executor.mapException(exception);
        }
    }

    private static FineractJobRun run(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String status = text(node, "status");
        Instant startedAt = instant(node.path("jobRunStartTime"));
        Instant endedAt = instant(node.path("jobRunEndTime"));
        if (status == null && startedAt == null && endedAt == null) {
            return null;
        }
        return new FineractJobRun(status, startedAt, endedAt);
    }

    /**
     * Fineract 1.15 serialize {@code java.util.Date} bằng {@code DateTimeFormatter.ISO_INSTANT} (UTC, hậu tố Z). Bản cũ
     * có thể trả offset (+05:30) hoặc epoch millis; đọc được cả ba, chuỗi khác coi như không có thời điểm.
     */
    static Instant instant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return Instant.ofEpochMilli(node.asLong());
        }
        String value = node.asText("").trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notInstant) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException unknownFormat) {
                return null;
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().trim() : null;
    }

    private static FineractIntegrationException invalid(String message) {
        return new FineractIntegrationException("FINERACT_RESPONSE_INVALID", message, false, null);
    }
}
