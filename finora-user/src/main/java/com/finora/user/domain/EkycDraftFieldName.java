package com.finora.user.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Các trường đọc từ thẻ, theo đúng thứ tự và tên JSON của hợp đồng EKYC-002 (mục 3.3).
 * <p>
 * {@code editableWhenAiOnly}: người dùng chỉ được sửa trường văn bản tự do mà <b>chỉ</b> AI đọc ra.
 * Số định danh, ngày tháng và giới tính có nguồn tất định (QR/MRZ/cấu trúc số) nên không bao giờ
 * được sửa — nếu cho sửa thì kẻ gian chỉ cần quét thẻ người khác rồi gõ đè số của mình.
 */
public enum EkycDraftFieldName {
    ID_NUMBER("idNumber", "id_number", false),
    FULL_NAME("fullName", "full_name", true),
    DATE_OF_BIRTH("dateOfBirth", "date_of_birth", false),
    GENDER("gender", "gender", false),
    ADDRESS("address", "address", true),
    PLACE_OF_ORIGIN("placeOfOrigin", "place_of_origin", true),
    BIRTH_REGISTRATION_PLACE("birthRegistrationPlace", "birth_registration_place", true),
    ISSUE_DATE("issueDate", "issue_date", false),
    EXPIRY_DATE("expiryDate", "expiry_date", false);

    /** Nguồn duy nhất cho phép người dùng sửa. */
    public static final String SOURCE_AI = "AI";
    /** Nguồn gán cho trường người dùng đã sửa. */
    public static final String SOURCE_USER = "USER";

    private final String jsonName;
    private final String aiName;
    private final boolean editableWhenAiOnly;

    EkycDraftFieldName(String jsonName, String aiName, boolean editableWhenAiOnly) {
        this.jsonName = jsonName;
        this.aiName = aiName;
        this.editableWhenAiOnly = editableWhenAiOnly;
    }

    /** Tên trường trong API FINORA (camelCase). */
    public String jsonName() {
        return jsonName;
    }

    /** Tên trường trong response của finora-ai (snake_case). */
    public String aiName() {
        return aiName;
    }

    /** Trường sửa được khi và chỉ khi nằm trong nhóm văn bản tự do và nguồn đúng bằng {@code ["AI"]}. */
    public boolean isEditable(EkycDraftField field) {
        return editableWhenAiOnly
                && field != null
                && field.value() != null
                && List.of(SOURCE_AI).equals(field.sources());
    }

    public static Optional<EkycDraftFieldName> fromJsonName(String jsonName) {
        return Arrays.stream(values()).filter(name -> name.jsonName.equals(jsonName)).findFirst();
    }
}
