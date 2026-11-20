package com.finora.user.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.finora.user.domain.enums.EkycDocumentDecision;
import com.finora.user.domain.enums.EkycDraftFieldName;
import com.finora.user.domain.enums.EkycLivenessResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bản nháp danh tính của một lượt eKYC: những gì đọc được từ thẻ, kết quả liveness và các mã lý do.
 * <p>
 * Bản nháp sống ở kho tạm {@code user_temporary_entries} (mã hoá, TTL 15 phút) trong lúc người dùng làm
 * tiếp liveness và soát lại;
 * client không gửi lại dữ liệu khi xác nhận nên không sửa được thông tin đã đọc (ngoài trường
 * {@link EkycDraftFieldName#isEditable được phép sửa}). Khi hồ sơ cần duyệt tay, bản nháp — đã bỏ
 * embedding khuôn mặt — được mã hoá vào {@code ekyc_attempts.draft_encrypted}.
 *
 * @param attemptId                 mã công khai của lượt (khớp object key trên kho lưu trữ)
 * @param fields                    trường theo {@link EkycDraftFieldName#jsonName()}, giữ thứ tự hợp đồng
 * @param noExpiry                  thẻ ghi "không thời hạn" (khi đó {@code expiryDate} không phải ngày)
 * @param portraitEmbeddingB64      embedding SFace của ảnh chân dung trên thẻ — dữ liệu sinh trắc, chỉ ở kho tạm
 * @param livenessResult            {@code null} khi chưa có kết luận liveness
 * @param livenessRound             số lần đã gửi video, dùng đặt tên object không trùng nhau
 * @param editedFields              trường người dùng đã sửa (nguồn đổi thành {@code USER})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EkycIdentityDraft(
        UUID attemptId,
        String cardType,
        EkycDocumentDecision documentDecision,
        List<String> documentReasons,
        List<String> documentWarnings,
        JsonNode documentChecks,
        String documentThresholdsVersion,
        Map<String, EkycDraftField> fields,
        boolean noExpiry,
        String portraitEmbeddingB64,
        EkycLivenessResult livenessResult,
        Double faceMatchScore,
        List<String> livenessReasons,
        String livenessThresholdsVersion,
        String livenessCaptureMode,
        int livenessRound,
        List<String> editedFields
) {

    /**
     * Mã lý do đánh dấu phần việc AI không làm được vì finora-ai gặp sự cố: trong {@code documentReasons} là bước
     * đọc thẻ (bản nháp không có trường nào, nhân viên phải đọc thẻ), trong {@code livenessReasons} là bước so mặt
     * (không có điểm so mặt, nhân viên phải tự so). Cùng tên với mã kết quả {@code AI_UNAVAILABLE}.
     */
    public static final String AI_UNAVAILABLE_REASON = "AI_UNAVAILABLE";

    /**
     * Mã lý do trong {@code documentReasons}: người dùng đã chụp lại nhiều lần mà vẫn báo thông tin đọc từ thẻ chưa đúng
     * nên hồ sơ đi duyệt tay; nhân viên đối chiếu ảnh thẻ và được sửa mọi trường.
     */
    public static final String USER_REPORTED_INCORRECT_REASON = "USER_REPORTED_INCORRECT";

    /**
     * Bản nháp của lượt chuyển duyệt tay ngay ở bước đọc thẻ vì AI lỗi: chưa có trường nào, không có embedding
     * chân dung (nên bước liveness sau đó không gọi AI so mặt được). Quyết định giấy tờ luôn {@code NEEDS_REVIEW}.
     */
    public static EkycIdentityDraft withoutAiReading(UUID attemptId) {
        Map<String, EkycDraftField> empty = new LinkedHashMap<>();
        for (EkycDraftFieldName name : EkycDraftFieldName.values()) {
            empty.put(name.jsonName(), new EkycDraftField(null, List.of()));
        }
        return new EkycIdentityDraft(attemptId, null, EkycDocumentDecision.NEEDS_REVIEW,
                List.of(AI_UNAVAILABLE_REASON), List.of(), null, null, empty, false, null,
                null, null, List.of(), null, null, 0, List.of());
    }

    public EkycIdentityDraft {
        // Jackson đọc JSON null thành NullNode cho kiểu JsonNode; chuẩn hoá để bản đọc lại bằng bản gốc.
        if (documentChecks != null && (documentChecks.isNull() || documentChecks.isMissingNode())) {
            documentChecks = null;
        }
        documentReasons = copy(documentReasons);
        documentWarnings = copy(documentWarnings);
        livenessReasons = copy(livenessReasons);
        editedFields = copy(editedFields);
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** Trường theo tên; {@code null} nếu AI không trả trường đó. */
    public EkycDraftField field(EkycDraftFieldName name) {
        return fields.get(name.jsonName());
    }

    /** Giá trị trường theo tên; {@code null} nếu không có. */
    public String value(EkycDraftFieldName name) {
        EkycDraftField field = field(name);
        return field == null ? null : field.value();
    }

    /**
     * Phiên bản ngưỡng gộp của hai bước để lưu vào {@code ekyc_attempts.thresholds_version}.
     */
    public String combinedThresholdsVersion() {
        if (livenessThresholdsVersion == null || livenessThresholdsVersion.isBlank()) {
            return documentThresholdsVersion;
        }
        if (documentThresholdsVersion == null || documentThresholdsVersion.isBlank()) {
            return livenessThresholdsVersion;
        }
        return documentThresholdsVersion + "," + livenessThresholdsVersion;
    }

    /** Thẻ chưa được AI đọc (AI lỗi ở bước thẻ): nhân viên phải đọc thẻ và nhập trường định danh. */
    @JsonIgnore
    public boolean cardReadWithoutAi() {
        return documentReasons.contains(AI_UNAVAILABLE_REASON);
    }

    /** Hồ sơ có ít nhất một bước AI không làm được vì sự cố (đọc thẻ hoặc so mặt). */
    @JsonIgnore
    public boolean reviewedWithoutAi() {
        return cardReadWithoutAi() || livenessReasons.contains(AI_UNAVAILABLE_REASON);
    }

    /** Người dùng báo thông tin đọc từ thẻ chưa đúng ({@link #USER_REPORTED_INCORRECT_REASON}). */
    @JsonIgnore
    public boolean userReportedIncorrect() {
        return documentReasons.contains(USER_REPORTED_INCORRECT_REASON);
    }

    /**
     * Video đã lưu làm bằng chứng nhưng không có kết quả AI: không có kết luận liveness, không có điểm so mặt,
     * lý do duy nhất là {@link #AI_UNAVAILABLE_REASON}.
     */
    public EkycIdentityDraft withLivenessWithoutAi(String captureMode, int round) {
        return withLiveness(null, null, List.of(AI_UNAVAILABLE_REASON), null, captureMode, round);
    }

    /**
     * Nhân viên điền trường định danh còn trống (hồ sơ AI lỗi); nguồn của trường đó là {@code STAFF}. Kiểm tra hợp
     * lệ nằm ở service duyệt, hàm này chỉ ghi giá trị.
     */
    public EkycIdentityDraft withStaffFields(String staffCardType, Map<EkycDraftFieldName, String> values,
                                             boolean staffNoExpiry) {
        Map<String, EkycDraftField> updated = new LinkedHashMap<>(fields);
        values.forEach((name, value) -> updated.put(name.jsonName(),
                new EkycDraftField(value, List.of(EkycDraftFieldName.SOURCE_STAFF))));
        return new EkycIdentityDraft(attemptId, cardType == null ? staffCardType : cardType, documentDecision,
                documentReasons, documentWarnings, documentChecks, documentThresholdsVersion, updated,
                noExpiry || staffNoExpiry, portraitEmbeddingB64, livenessResult, faceMatchScore, livenessReasons,
                livenessThresholdsVersion, livenessCaptureMode, livenessRound, editedFields);
    }

    /**
     * Nhân viên sửa hồ sơ người dùng báo sai: ghi đè trường (nguồn {@code STAFF}), loại thẻ (nếu có) và cờ "không thời
     * hạn" đúng như nhân viên chọn. Kiểm tra hợp lệ nằm ở {@code EkycStaffIdentityRules}, hàm này chỉ ghi giá trị.
     */
    public EkycIdentityDraft withStaffCorrections(String staffCardType, Map<EkycDraftFieldName, String> values,
                                                  boolean staffNoExpiry) {
        Map<String, EkycDraftField> updated = new LinkedHashMap<>(fields);
        values.forEach((name, value) -> updated.put(name.jsonName(),
                new EkycDraftField(value, List.of(EkycDraftFieldName.SOURCE_STAFF))));
        return new EkycIdentityDraft(attemptId, staffCardType == null ? cardType : staffCardType, documentDecision,
                documentReasons, documentWarnings, documentChecks, documentThresholdsVersion, updated,
                staffNoExpiry, portraitEmbeddingB64, livenessResult, faceMatchScore, livenessReasons,
                livenessThresholdsVersion, livenessCaptureMode, livenessRound, editedFields);
    }

    /** Ghi kết luận liveness mới (PASS/REVIEW/FAIL) và tăng số lần gửi video. */
    public EkycIdentityDraft withLiveness(EkycLivenessResult result, Double score, List<String> reasons,
                                          String thresholdsVersion, String captureMode, int round) {
        return new EkycIdentityDraft(attemptId, cardType, documentDecision, documentReasons, documentWarnings,
                documentChecks, documentThresholdsVersion, fields, noExpiry, portraitEmbeddingB64,
                result, score, reasons, thresholdsVersion, captureMode, round, editedFields);
    }

    /** Video phải quay lại (không có kết luận mới): chỉ tăng số lần gửi, giữ kết luận cũ. */
    public EkycIdentityDraft withLivenessRound(int round) {
        return new EkycIdentityDraft(attemptId, cardType, documentDecision, documentReasons, documentWarnings,
                documentChecks, documentThresholdsVersion, fields, noExpiry, portraitEmbeddingB64,
                livenessResult, faceMatchScore, livenessReasons, livenessThresholdsVersion, livenessCaptureMode,
                round, editedFields);
    }

    /**
     * Áp các trường người dùng sửa; nguồn của trường được sửa đổi thành {@code ["USER"]}.
     * Kiểm tra quyền sửa nằm ở service, hàm này chỉ ghi giá trị.
     */
    public EkycIdentityDraft withEdits(Map<EkycDraftFieldName, String> edits) {
        if (edits == null || edits.isEmpty()) {
            return this;
        }
        Map<String, EkycDraftField> updated = new LinkedHashMap<>(fields);
        List<String> edited = new ArrayList<>(editedFields);
        edits.forEach((name, value) -> {
            updated.put(name.jsonName(), new EkycDraftField(value, List.of(EkycDraftFieldName.SOURCE_USER)));
            if (!edited.contains(name.jsonName())) {
                edited.add(name.jsonName());
            }
        });
        return new EkycIdentityDraft(attemptId, cardType, documentDecision, documentReasons, documentWarnings,
                documentChecks, documentThresholdsVersion, updated, noExpiry, portraitEmbeddingB64,
                livenessResult, faceMatchScore, livenessReasons, livenessThresholdsVersion, livenessCaptureMode,
                livenessRound, edited);
    }

    /** Đánh dấu người dùng báo thông tin đọc từ thẻ chưa đúng: thêm {@link #USER_REPORTED_INCORRECT_REASON} (không trùng). */
    public EkycIdentityDraft withUserReportedIncorrect() {
        if (userReportedIncorrect()) {
            return this;
        }
        List<String> reasons = new ArrayList<>(documentReasons);
        reasons.add(USER_REPORTED_INCORRECT_REASON);
        return new EkycIdentityDraft(attemptId, cardType, documentDecision, reasons, documentWarnings,
                documentChecks, documentThresholdsVersion, fields, noExpiry, portraitEmbeddingB64,
                livenessResult, faceMatchScore, livenessReasons, livenessThresholdsVersion, livenessCaptureMode,
                livenessRound, editedFields);
    }

    /**
     * Bản để lưu lâu dài (DB, duyệt tay): bỏ embedding khuôn mặt. Nhân viên duyệt xem ảnh/video,
     * không cần vector sinh trắc; giữ nó lâu hơn bước xác minh là vi phạm tối thiểu hoá dữ liệu.
     */
    public EkycIdentityDraft withoutBiometrics() {
        return new EkycIdentityDraft(attemptId, cardType, documentDecision, documentReasons, documentWarnings,
                documentChecks, documentThresholdsVersion, fields, noExpiry, null,
                livenessResult, faceMatchScore, livenessReasons, livenessThresholdsVersion, livenessCaptureMode,
                livenessRound, editedFields);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
