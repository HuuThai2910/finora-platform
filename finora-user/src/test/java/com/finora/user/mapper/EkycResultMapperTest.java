package com.finora.user.mapper;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.user.domain.EkycDocumentDecision;
import com.finora.user.domain.EkycDraftFieldName;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.EkycLivenessResult;
import com.finora.user.dto.response.EkycNextStep;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.support.EkycTestData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Khuôn {@code EkycResultResponse} và quy tắc {@code editable} (EKYC-002 mục 3.3). */
class EkycResultMapperTest {

    private final EkycIdentityDraft draft =
            EkycTestData.draft(UUID.randomUUID(), EkycDocumentDecision.AUTO_OK, EkycLivenessResult.PASS);

    @Test
    void editableOnlyForFreeTextFieldsReadByAiAlone() {
        EkycResultResponse.DraftView view = EkycResultMapper.toDraftView(draft);

        assertThat(view.address().editable()).isTrue();
        assertThat(view.birthRegistrationPlace().editable()).isTrue();
        assertThat(view.fullName().editable()).as("nguồn QR").isFalse();
        assertThat(view.idNumber().editable()).isFalse();
        assertThat(view.placeOfOrigin()).as("không thu thập").isNull();
    }

    @Test
    void userEditedFieldsBecomeUserSourcedAndLocked() {
        EkycIdentityDraft edited = draft.withEdits(Map.of(EkycDraftFieldName.ADDRESS, "Số 1 Lê Lợi"));

        EkycResultResponse.DraftFieldView address = EkycResultMapper.toDraftView(edited).address();

        assertThat(address.value()).isEqualTo("Số 1 Lê Lợi");
        assertThat(address.sources()).containsExactly("USER");
        assertThat(address.editable()).isFalse();
        assertThat(edited.editedFields()).containsExactly("address");
    }

    @Test
    void simpleResultHasStableShapeWithEmptyLists() {
        EkycResultResponse response = EkycResultMapper.simple(EkycStatus.PENDING, EkycResultCode.CARD_EXPIRED);

        assertThat(response.message()).isEqualTo("Thẻ căn cước đã hết hạn. Vui lòng dùng thẻ còn hiệu lực.");
        assertThat(response.retake()).isEmpty();
        assertThat(response.ocrWarnings()).isEmpty();
        assertThat(response.draft()).isNull();
        assertThat(response.nextStep()).isEqualTo(EkycNextStep.NONE);
        assertThat(response.attemptsLeft()).isNull();
    }

    @Test
    void livenessReasonsKeepOnlyCodesWithGuidance() {
        assertThat(EkycResultMapper.toReasons(List.of("ACTIONS_MATCH", "BLURRY", "BLURRY", "STILLS_MODE")))
                .singleElement()
                .satisfies(reason -> {
                    assertThat(reason.code()).isEqualTo("BLURRY");
                    assertThat(reason.message()).isEqualTo("Video bị mờ. Giữ máy yên và quay đầu chậm hơn.");
                });
    }

    @Test
    void reviewRequiredWhenDocumentNeedsReviewOrLivenessIsGray() {
        assertThat(EkycResultMapper.requiresReview(draft)).isFalse();
        assertThat(EkycResultMapper.requiresReview(
                EkycTestData.draft(UUID.randomUUID(), EkycDocumentDecision.NEEDS_REVIEW, EkycLivenessResult.PASS))).isTrue();
        assertThat(EkycResultMapper.requiresReview(
                EkycTestData.draft(UUID.randomUUID(), EkycDocumentDecision.AUTO_OK, EkycLivenessResult.REVIEW))).isTrue();
    }
}
