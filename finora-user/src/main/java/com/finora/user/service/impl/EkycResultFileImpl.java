package com.finora.user.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycDraftField;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.service.EkycAttemptService;
import com.finora.user.service.EkycResultFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dựng {@code result.json} — bản tóm tắt bất biến của một lượt đã có kết quả cuối, lưu cạnh ảnh/video.
 * <p>
 * Chứa quyết định, mã lý do, điểm so khuôn mặt, các check QR/MRZ, phiên bản ngưỡng, nguồn của từng
 * trường và SHA-256 của từng file — đủ để tái lập vì sao hệ thống kết luận như vậy. <b>Không</b> chứa
 * giá trị trường (số định danh, họ tên, địa chỉ) hay embedding khuôn mặt.
 */
@Component
@RequiredArgsConstructor
public class EkycResultFileImpl implements EkycResultFile {

    static final String SCHEMA = "finora-ekyc-result/1";

    private final ObjectMapper objectMapper;

    @Override
    public byte[] build(EkycAttempt attempt, EkycIdentityDraft draft, String outcome, List<EkycEvidence> files,
                        Instant decidedAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("attemptId", attempt.getPublicId().toString());
        root.put("userProfileId", attempt.getUserProfileId());
        root.put("consentId", attempt.getConsentId());
        root.put("outcome", outcome);
        root.put("decidedAt", decidedAt.toString());

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("cardType", draft.cardType());
        document.put("decision", draft.documentDecision() == null ? null : draft.documentDecision().name());
        document.put("reasons", draft.documentReasons());
        document.put("warnings", draft.documentWarnings());
        document.put("checks", draft.documentChecks());
        document.put("noExpiry", draft.noExpiry());
        document.put("thresholdsVersion", draft.documentThresholdsVersion());
        root.put("document", document);

        Map<String, Object> liveness = new LinkedHashMap<>();
        liveness.put("result", draft.livenessResult() == null ? null : draft.livenessResult().name());
        liveness.put("reasons", draft.livenessReasons());
        liveness.put("faceMatchScore", draft.faceMatchScore());
        liveness.put("faceMatchZone", EkycAttemptService.faceMatchZone(draft.livenessReasons()));
        liveness.put("captureMode", draft.livenessCaptureMode());
        liveness.put("submissions", draft.livenessRound());
        liveness.put("thresholdsVersion", draft.livenessThresholdsVersion());
        root.put("liveness", liveness);

        Map<String, List<String>> fieldSources = new LinkedHashMap<>();
        for (Map.Entry<String, EkycDraftField> entry : draft.fields().entrySet()) {
            fieldSources.put(entry.getKey(), entry.getValue().sources());
        }
        root.put("fieldSources", fieldSources);
        root.put("editedFields", draft.editedFields());

        root.put("files", files.stream().map(file -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", file.getKind().name());
            item.put("label", file.getLabel());
            item.put("objectKey", file.getObjectKey());
            item.put("sha256", file.getSha256());
            item.put("sizeBytes", file.getSizeBytes());
            item.put("contentType", file.getContentType());
            return item;
        }).toList());

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không dựng được result.json", e);
        }
    }
}
