package com.finora.user.mapper;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.user.domain.enums.EkycDocumentDecision;
import com.finora.user.domain.enums.EkycDraftFieldName;
import com.finora.user.domain.enums.EkycLivenessResult;
import com.finora.user.domain.model.EkycDraftField;
import com.finora.user.domain.model.EkycIdentityDraft;
import com.finora.user.dto.response.EkycNextStep;
import com.finora.user.dto.response.EkycResultResponse.DraftFieldView;
import com.finora.user.dto.response.EkycResultResponse.DraftView;
import com.finora.user.dto.response.EkycResultResponse.LivenessReason;
import com.finora.user.dto.response.EkycResultResponse.LivenessView;
import com.finora.user.dto.response.EkycResultResponse.RetakeItem;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.util.EkycMessages;

import java.util.List;

/**
 * Dựng {@link EkycResultResponse} theo đúng khuôn hợp đồng cho từng tình huống, để ba bước quét thẻ,
 * liveness và xác nhận trả cùng một hình dạng JSON.
 */
public final class EkycResultMapper {

    private EkycResultMapper() {
    }

    /** Lần gọi dừng ở một mã, không có dữ liệu kèm theo; hồ sơ giữ nguyên trạng thái. */
    public static EkycResultResponse simple(EkycStatus status, EkycResultCode code, EkycNextStep nextStep) {
        return new EkycResultResponse(status, code, EkycMessages.result(code), List.of(), null, false,
                List.of(), null, nextStep, null, null);
    }

    public static EkycResultResponse simple(EkycStatus status, EkycResultCode code) {
        return simple(status, code, EkycNextStep.NONE);
    }

    /**
     * AI không phục vụ được: hồ sơ giữ nguyên, lượt không bị tính. {@code manualReviewAvailable} bật khi AI đã lỗi
     * đủ số lần liên tiếp — client hiện nút gửi nhân viên duyệt.
     */
    public static EkycResultResponse aiUnavailable(EkycStatus status, EkycNextStep nextStep,
                                                   boolean manualReviewAvailable) {
        return new EkycResultResponse(status, EkycResultCode.AI_UNAVAILABLE,
                EkycMessages.result(EkycResultCode.AI_UNAVAILABLE), List.of(), null, false, List.of(), null,
                nextStep, null, null, manualReviewAvailable, false);
    }

    /** Bước thẻ chuyển duyệt tay vì AI lỗi: ảnh đã lưu, không có trường đọc được; tiếp theo quay video. */
    public static EkycResultResponse manualDraftReady(EkycStatus status) {
        return new EkycResultResponse(status, EkycResultCode.DRAFT_READY, EkycMessages.MANUAL_DRAFT_READY,
                List.of(), null, true, List.of(), null, EkycNextStep.LIVENESS, null, null, false, true);
    }

    public static EkycResultResponse alreadyVerified() {
        return new EkycResultResponse(EkycStatus.VERIFIED, EkycResultCode.VERIFIED, EkycMessages.ALREADY_VERIFIED,
                List.of(), null, false, List.of(), null, EkycNextStep.NONE, null, null);
    }

    public static EkycResultResponse verified() {
        return simple(EkycStatus.VERIFIED, EkycResultCode.VERIFIED);
    }

    public static EkycResultResponse reviewPending() {
        return new EkycResultResponse(EkycStatus.MANUAL_REVIEW, EkycResultCode.REVIEW_PENDING,
                EkycMessages.result(EkycResultCode.REVIEW_PENDING), List.of(), null, true, List.of(), null,
                EkycNextStep.NONE, null, null);
    }

    /** AI cần chụp lại thẻ: thông điệp chung lấy theo lý do đầu tiên để người dùng biết sửa gì trước. */
    public static EkycResultResponse documentRetake(EkycStatus status, String cardType, List<RetakeItem> retake) {
        String message = retake.isEmpty()
                ? EkycMessages.result(EkycResultCode.RETAKE)
                : retake.get(0).message();
        return new EkycResultResponse(status, EkycResultCode.RETAKE, message, List.of(), cardType, false,
                retake, null, EkycNextStep.NONE, null, null);
    }

    /**
     * Đã đọc xong thẻ, hồ sơ chưa lưu gì. Bản nháp chờ liveness ({@code nextStep=LIVENESS}) hoặc, khi đã tắt liveness,
     * chờ người dùng soát và xác nhận ({@code nextStep=CONFIRM}).
     */
    public static EkycResultResponse draftReady(EkycStatus status, EkycIdentityDraft draft, List<String> warnings,
                                                EkycNextStep nextStep) {
        String message = nextStep == EkycNextStep.LIVENESS
                ? EkycMessages.result(EkycResultCode.DRAFT_READY)
                : EkycMessages.DRAFT_READY_CONFIRM;
        // Không có liveness thì người dùng xác nhận là xác minh luôn: không báo trước "sẽ duyệt thêm".
        boolean reviewRequired = nextStep == EkycNextStep.LIVENESS && requiresReview(draft);
        return new EkycResultResponse(status, EkycResultCode.DRAFT_READY, message, warnings, draft.cardType(),
                reviewRequired, List.of(), toDraftView(draft), nextStep, null, null);
    }

    /**
     * Kết quả một lần gửi video.
     *
     * @param livenessLabel {@code PASSED | REVIEW | FAILED | RETAKE}
     * @param includeDraft  gửi kèm bản nháp khi người dùng chuyển sang màn soát và xác nhận
     */
    public static EkycResultResponse liveness(EkycStatus status, EkycResultCode code, String message,
                                              EkycIdentityDraft draft, EkycNextStep nextStep, Integer attemptsLeft,
                                              String livenessLabel, Double faceMatchScore, List<String> reasons,
                                              boolean includeDraft) {
        boolean reviewRequired = code == EkycResultCode.LIVENESS_REVIEW
                || code == EkycResultCode.REVIEW_PENDING
                || (includeDraft && draft != null && requiresReview(draft));
        return new EkycResultResponse(status, code, message, List.of(),
                draft == null ? null : draft.cardType(), reviewRequired, List.of(),
                includeDraft && draft != null ? toDraftView(draft) : null,
                nextStep, attemptsLeft, new LivenessView(livenessLabel, faceMatchScore, toReasons(reasons)));
    }

    /** Hồ sơ sẽ vào duyệt tay sau khi xác nhận: giấy tờ chưa AUTO_OK hoặc liveness ở vùng xám. */
    public static boolean requiresReview(EkycIdentityDraft draft) {
        return draft.documentDecision() != EkycDocumentDecision.AUTO_OK
                || draft.livenessResult() == EkycLivenessResult.REVIEW;
    }

    /** Chỉ giữ mã có thông điệp hướng dẫn; mã tích cực (ACTIONS_MATCH…) không cần hiển thị. */
    public static List<LivenessReason> toReasons(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream()
                .distinct()
                .flatMap(code -> EkycMessages.livenessReason(code).map(msg -> new LivenessReason(code, msg)).stream())
                .toList();
    }

    public static DraftView toDraftView(EkycIdentityDraft draft) {
        if (draft == null) {
            return null;
        }
        return new DraftView(
                view(draft, EkycDraftFieldName.ID_NUMBER),
                view(draft, EkycDraftFieldName.FULL_NAME),
                view(draft, EkycDraftFieldName.DATE_OF_BIRTH),
                view(draft, EkycDraftFieldName.GENDER),
                view(draft, EkycDraftFieldName.ADDRESS),
                view(draft, EkycDraftFieldName.PLACE_OF_ORIGIN),
                view(draft, EkycDraftFieldName.BIRTH_REGISTRATION_PLACE),
                view(draft, EkycDraftFieldName.ISSUE_DATE),
                view(draft, EkycDraftFieldName.EXPIRY_DATE));
    }

    private static DraftFieldView view(EkycIdentityDraft draft, EkycDraftFieldName name) {
        EkycDraftField field = draft.field(name);
        if (field == null || field.isNotCollected()) {
            return null;
        }
        return new DraftFieldView(field.value(), field.sources(), name.isEditable(field));
    }
}
