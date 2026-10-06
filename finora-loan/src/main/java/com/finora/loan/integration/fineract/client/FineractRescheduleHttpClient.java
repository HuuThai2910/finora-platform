package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.config.FineractBookingProperties;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Adapter API reschedule của Fineract 1.15; chỉ trả contract đã lọc cho domain Loan. */
@Component
public class FineractRescheduleHttpClient implements FineractRescheduleGateway {
    private final RestClient client;
    private final FineractRequestExecutor executor;
    private final FineractBookingProperties format;

    public FineractRescheduleHttpClient(RestClient fineractRestClient, FineractRequestExecutor executor,
            FineractBookingProperties format) {
        this.client = fineractRestClient;
        this.executor = executor;
        this.format = format;
    }

    @Override
    public Optional<CoreReschedule> findByLoanAndMarker(Long fineractLoanId, String marker) {
        JsonNode response = executor.execute(FineractCallGroup.RESTRUCTURING, "find-reschedule", () ->
                client.get().uri(uri -> uri.path("/rescheduleloans")
                                .queryParam("loanId", fineractLoanId).build())
                        .headers(executor::authenticate).retrieve().body(JsonNode.class));
        JsonNode items = response != null && response.isArray() ? response
                : response == null ? null : response.path("pageItems");
        if (items == null || !items.isArray()) return Optional.empty();
        for (JsonNode item : items) {
            if (item.path("rescheduleReasonComment").asText("").contains(marker)) {
                return Optional.of(parse(item));
            }
        }
        return Optional.empty();
    }

    @Override
    public CoreReschedule create(CreateRescheduleCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", command.fineractLoanId());
        payload.put("rescheduleFromDate", command.rescheduleFromDate().toString());
        payload.put("rescheduleReasonId", command.reasonId());
        payload.put("rescheduleReasonComment", command.marker());
        payload.put("submittedOnDate", command.submittedOnDate().toString());
        payload.put("dateFormat", format.dateFormat());
        payload.put("locale", format.locale());
        if (command.adjustedDueDate() != null) {
            payload.put("adjustedDueDate", command.adjustedDueDate().toString());
        }
        if (command.extraTerms() != null) payload.put("extraTerms", command.extraTerms());
        JsonNode response = executor.execute(FineractCallGroup.RESTRUCTURING, "create-reschedule", () ->
                client.post().uri("/rescheduleloans").headers(executor::authenticate)
                        .body(payload).retrieve().body(JsonNode.class));
        long id = resourceId(response);
        return new CoreReschedule(id, "PENDING_APPROVAL");
    }

    @Override
    public CoreReschedule read(Long coreRescheduleId) {
        JsonNode response = executor.execute(FineractCallGroup.RESTRUCTURING, "read-reschedule", () ->
                client.get().uri("/rescheduleloans/{id}", coreRescheduleId)
                        .headers(executor::authenticate).retrieve().body(JsonNode.class));
        return parse(response);
    }

    @Override
    public void approve(Long coreRescheduleId, LocalDate approvedOnDate) {
        Map<String, Object> payload = Map.of(
                "approvedOnDate", approvedOnDate.toString(),
                "dateFormat", format.dateFormat(),
                "locale", format.locale());
        executor.execute(FineractCallGroup.RESTRUCTURING, "approve-reschedule", () ->
                client.post().uri(uri -> uri.path("/rescheduleloans/{id}")
                                .queryParam("command", "approve").build(coreRescheduleId))
                        .headers(executor::authenticate).body(payload).retrieve().body(JsonNode.class));
    }

    private CoreReschedule parse(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new FineractIntegrationException("FINERACT_RESPONSE_INVALID",
                    "Fineract không trả dữ liệu yêu cầu cơ cấu", false, null);
        }
        long id = value.path("id").asLong(value.path("resourceId").asLong(0));
        if (id <= 0) throw new FineractIntegrationException("FINERACT_RESPONSE_INVALID",
                "Fineract không trả reschedule ID", false, null);
        JsonNode status = value.path("statusEnum");
        String code = status.path("code").asText(status.path("value").asText("UNKNOWN"));
        if (status.path("approved").asBoolean(false)) code = "APPROVED";
        else if (status.path("rejected").asBoolean(false)) code = "REJECTED";
        else if (status.path("pendingApproval").asBoolean(false)) code = "PENDING_APPROVAL";
        return new CoreReschedule(id, code);
    }

    private long resourceId(JsonNode response) {
        long id = response == null ? 0 : response.path("resourceId").asLong(0);
        if (id <= 0) throw new FineractIntegrationException("FINERACT_RESPONSE_INVALID",
                "Fineract không trả resourceId của yêu cầu cơ cấu", false, null);
        return id;
    }
}

