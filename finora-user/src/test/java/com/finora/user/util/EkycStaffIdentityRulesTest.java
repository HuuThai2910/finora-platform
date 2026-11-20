package com.finora.user.util;

import com.finora.common.exception.BusinessException;
import com.finora.user.domain.enums.EkycDocumentDecision;
import com.finora.user.domain.enums.EkycDraftFieldName;
import com.finora.user.domain.model.EkycIdentityDraft;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Trường định danh nhân viên nhập cho hồ sơ AI gặp sự cố. */
class EkycStaffIdentityRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final UUID ATTEMPT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static Map<EkycDraftFieldName, String> fullValues() {
        Map<EkycDraftFieldName, String> values = new EnumMap<>(EkycDraftFieldName.class);
        values.put(EkycDraftFieldName.ID_NUMBER, EkycTestData.ID_NUMBER);
        values.put(EkycDraftFieldName.FULL_NAME, "  NGUYỄN   VĂN A ");
        values.put(EkycDraftFieldName.DATE_OF_BIRTH, "15/03/1995");
        values.put(EkycDraftFieldName.GENDER, "Nam");
        values.put(EkycDraftFieldName.ISSUE_DATE, "01/07/2021");
        values.put(EkycDraftFieldName.EXPIRY_DATE, "15/03/2035");
        return values;
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void emptyDraftNeedsCardTypeAndAllRequiredFields() {
        EkycIdentityDraft empty = EkycIdentityDraft.withoutAiReading(ATTEMPT_ID);

        assertThat(EkycStaffIdentityRules.missingRequiredFields(empty)).containsExactly(
                "cardType", "idNumber", "fullName", "dateOfBirth", "gender", "issueDate", "expiryDate");
        assertThat(EkycStaffIdentityRules.missingRequiredFields(
                EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.AUTO_OK, null))).isEmpty();
    }

    @Test
    void completeFillsFieldsWithStaffSourceAndNormalizesSpaces() {
        EkycIdentityDraft completed = EkycStaffIdentityRules.complete(EkycIdentityDraft.withoutAiReading(ATTEMPT_ID),
                "CCCD_CHIP_2021", fullValues(), false, TODAY);

        assertThat(completed.cardType()).isEqualTo("CCCD_CHIP_2021");
        assertThat(completed.value(EkycDraftFieldName.FULL_NAME)).isEqualTo("NGUYỄN VĂN A");
        assertThat(completed.field(EkycDraftFieldName.ID_NUMBER).sources()).containsExactly("STAFF");
        assertThat(completed.noExpiry()).isFalse();
        assertThat(EkycStaffIdentityRules.missingRequiredFields(completed)).isEmpty();
    }

    @Test
    void noExpiryCardSkipsExpiryDate() {
        Map<EkycDraftFieldName, String> values = fullValues();
        values.remove(EkycDraftFieldName.EXPIRY_DATE);

        EkycIdentityDraft completed = EkycStaffIdentityRules.complete(EkycIdentityDraft.withoutAiReading(ATTEMPT_ID),
                "CAN_CUOC_2024", values, true, TODAY);

        assertThat(completed.noExpiry()).isTrue();
        assertThat(completed.value(EkycDraftFieldName.EXPIRY_DATE)).isEqualTo("Không thời hạn");
    }

    @Test
    void missingRequiredFieldIsListedInVietnamese() {
        Map<EkycDraftFieldName, String> values = fullValues();
        values.remove(EkycDraftFieldName.GENDER);

        assertThatThrownBy(() -> EkycStaffIdentityRules.complete(EkycIdentityDraft.withoutAiReading(ATTEMPT_ID),
                null, values, false, TODAY))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IDENTITY_REQUIRED");
                    assertThat(e.getMessage()).contains("Loại thẻ", "Giới tính").doesNotContain("Họ và tên");
                });
    }

    @Test
    void invalidValuesAreRefused() {
        EkycIdentityDraft empty = EkycIdentityDraft.withoutAiReading(ATTEMPT_ID);

        assertCode(() -> EkycStaffIdentityRules.complete(empty, "CMND", fullValues(), false, TODAY), "INVALID_IDENTITY");

        Map<EkycDraftFieldName, String> cmnd = fullValues();
        cmnd.put(EkycDraftFieldName.ID_NUMBER, "123456789");
        assertCode(() -> EkycStaffIdentityRules.complete(empty, "CCCD_CHIP_2021", cmnd, false, TODAY), "INVALID_IDENTITY");

        Map<EkycDraftFieldName, String> gender = fullValues();
        gender.put(EkycDraftFieldName.GENDER, "Khác");
        assertCode(() -> EkycStaffIdentityRules.complete(empty, "CCCD_CHIP_2021", gender, false, TODAY), "INVALID_IDENTITY");

        Map<EkycDraftFieldName, String> badDate = fullValues();
        badDate.put(EkycDraftFieldName.DATE_OF_BIRTH, "31/02/1995");
        assertCode(() -> EkycStaffIdentityRules.complete(empty, "CCCD_CHIP_2021", badDate, false, TODAY), "INVALID_IDENTITY");

        Map<EkycDraftFieldName, String> futureIssue = fullValues();
        futureIssue.put(EkycDraftFieldName.ISSUE_DATE, "01/01/2027");
        assertCode(() -> EkycStaffIdentityRules.complete(empty, "CCCD_CHIP_2021", futureIssue, false, TODAY), "INVALID_IDENTITY");
    }

    @Test
    void underageAndExpiredFollowSameRulesAsCardScan() {
        EkycIdentityDraft empty = EkycIdentityDraft.withoutAiReading(ATTEMPT_ID);

        Map<EkycDraftFieldName, String> minor = fullValues();
        minor.put(EkycDraftFieldName.DATE_OF_BIRTH, "18/11/2008");
        minor.put(EkycDraftFieldName.ISSUE_DATE, "01/07/2024");
        assertCode(() -> EkycStaffIdentityRules.complete(empty, "CCCD_CHIP_2021", minor, false, TODAY), "UNDERAGE");

        Map<EkycDraftFieldName, String> expired = fullValues();
        expired.put(EkycDraftFieldName.EXPIRY_DATE, "16/11/2026");
        assertCode(() -> EkycStaffIdentityRules.complete(empty, "CCCD_CHIP_2021", expired, false, TODAY), "CARD_EXPIRED");
    }

    @Test
    void fieldsAlreadyReadFromCardCannotBeOverwritten() {
        EkycIdentityDraft read = EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.AUTO_OK, null);
        Map<EkycDraftFieldName, String> values = new EnumMap<>(EkycDraftFieldName.class);
        values.put(EkycDraftFieldName.ID_NUMBER, EkycTestData.OTHER_ID_NUMBER);

        assertCode(() -> EkycStaffIdentityRules.complete(read, null, values, false, TODAY), "EDIT_NOT_ALLOWED");
        assertCode(() -> EkycStaffIdentityRules.complete(read, "CAN_CUOC_2024", Map.of(), false, TODAY), "EDIT_NOT_ALLOWED");

        // Trường còn trống (quê quán) thì nhân viên được bổ sung.
        Map<EkycDraftFieldName, String> origin = new EnumMap<>(EkycDraftFieldName.class);
        origin.put(EkycDraftFieldName.PLACE_OF_ORIGIN, "Hà Nội");
        assertThat(EkycStaffIdentityRules.complete(read, null, origin, false, TODAY)
                .value(EkycDraftFieldName.PLACE_OF_ORIGIN)).isEqualTo("Hà Nội");
    }

    @Test
    void correctOverwritesFieldsReadFromCardWithStaffSource() {
        EkycIdentityDraft read = EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.AUTO_OK, null);
        Map<EkycDraftFieldName, String> values = new EnumMap<>(EkycDraftFieldName.class);
        values.put(EkycDraftFieldName.FULL_NAME, "  NGUYỄN   VĂN AN ");
        values.put(EkycDraftFieldName.ID_NUMBER, EkycTestData.OTHER_ID_NUMBER);

        EkycIdentityDraft corrected = EkycStaffIdentityRules.correct(read, "CAN_CUOC_2024", values, false, TODAY);

        assertThat(corrected.value(EkycDraftFieldName.FULL_NAME)).isEqualTo("NGUYỄN VĂN AN");
        assertThat(corrected.field(EkycDraftFieldName.FULL_NAME).sources()).containsExactly("STAFF");
        assertThat(corrected.value(EkycDraftFieldName.ID_NUMBER)).isEqualTo(EkycTestData.OTHER_ID_NUMBER);
        assertThat(corrected.cardType()).isEqualTo("CAN_CUOC_2024");
        assertThat(corrected.field(EkycDraftFieldName.DATE_OF_BIRTH).sources())
                .as("trường không sửa giữ nguồn cũ").containsExactly("QR", "MRZ");
    }

    @Test
    void correctSwitchesNoExpiryBothWays() {
        EkycIdentityDraft read = EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.AUTO_OK, null);

        EkycIdentityDraft noExpiry = EkycStaffIdentityRules.correct(read, null, Map.of(), true, TODAY);
        assertThat(noExpiry.noExpiry()).isTrue();
        assertThat(noExpiry.value(EkycDraftFieldName.EXPIRY_DATE)).isEqualTo("Không thời hạn");

        Map<EkycDraftFieldName, String> expiry = new EnumMap<>(EkycDraftFieldName.class);
        expiry.put(EkycDraftFieldName.EXPIRY_DATE, "15/03/2040");
        EkycIdentityDraft dated = EkycStaffIdentityRules.correct(noExpiry, null, expiry, false, TODAY);
        assertThat(dated.noExpiry()).isFalse();
        assertThat(dated.value(EkycDraftFieldName.EXPIRY_DATE)).isEqualTo("15/03/2040");
    }

    @Test
    void correctFollowsSameBusinessRules() {
        EkycIdentityDraft read = EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.AUTO_OK, null);
        Map<EkycDraftFieldName, String> underage = new EnumMap<>(EkycDraftFieldName.class);
        underage.put(EkycDraftFieldName.DATE_OF_BIRTH, "01/01/2010");
        Map<EkycDraftFieldName, String> cmnd = new EnumMap<>(EkycDraftFieldName.class);
        cmnd.put(EkycDraftFieldName.ID_NUMBER, "123456789");

        assertCode(() -> EkycStaffIdentityRules.correct(read, null, underage, false, TODAY), "UNDERAGE");
        assertCode(() -> EkycStaffIdentityRules.correct(read, null, cmnd, false, TODAY), "INVALID_IDENTITY");
        assertCode(() -> EkycStaffIdentityRules.correct(read, "CMND", Map.of(), false, TODAY), "INVALID_IDENTITY");
    }
}
