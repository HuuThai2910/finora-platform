package com.finora.user.util;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.Gender;
import com.finora.common.exception.BusinessException;
import com.finora.user.domain.enums.EkycDraftFieldName;
import com.finora.user.domain.model.EkycIdentityDraft;
import com.finora.user.service.EkycIdentityService;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Luật cho trường định danh nhân viên nhập khi duyệt: hồ sơ AI gặp sự cố (điền chỗ trống, {@link #complete}) và hồ sơ
 * người dùng báo thông tin đọc từ thẻ chưa đúng (sửa bản nháp, {@link #correct}).
 * <p>
 * Nhân viên chỉ điền chỗ trống của bản nháp; trường đã có giá trị (đọc tất định từ QR/MRZ ở lượt AI còn chạy) không
 * được ghi đè. Giá trị nhân viên nhập qua cùng luật nghiệp vụ như kết quả AI ở bước quét thẻ: thẻ 12 số (không nhận
 * CMND), đủ 18 tuổi và thẻ còn hạn theo ngày Việt Nam. Trùng số với tài khoản khác do service duyệt kiểm (như cũ).
 */
public final class EkycStaffIdentityRules {

    /** Tên trường loại thẻ trong {@code requiredIdentityFields} — không thuộc bộ trường của bản nháp. */
    public static final String CARD_TYPE_FIELD = "cardType";

    /** Loại thẻ nhân viên được chọn; CMND không được nhận (giống luật ở bước quét thẻ). */
    public static final Set<String> ALLOWED_CARD_TYPES =
            Set.of("CCCD_CHIP_2021", "CAN_CUOC_2024", "CCCD_CHIP_UNKNOWN", "CCCD_BARCODE");

    /** Trường bắt buộc phải có giá trị trước khi duyệt; {@code EXPIRY_DATE} được miễn khi thẻ không thời hạn. */
    private static final List<EkycDraftFieldName> REQUIRED = List.of(
            EkycDraftFieldName.ID_NUMBER, EkycDraftFieldName.FULL_NAME, EkycDraftFieldName.DATE_OF_BIRTH,
            EkycDraftFieldName.GENDER, EkycDraftFieldName.ISSUE_DATE, EkycDraftFieldName.EXPIRY_DATE);

    private static final Map<EkycDraftFieldName, String> LABELS = new EnumMap<>(Map.of(
            EkycDraftFieldName.ID_NUMBER, "Số định danh",
            EkycDraftFieldName.FULL_NAME, "Họ và tên",
            EkycDraftFieldName.DATE_OF_BIRTH, "Ngày sinh",
            EkycDraftFieldName.GENDER, "Giới tính",
            EkycDraftFieldName.ADDRESS, "Nơi thường trú",
            EkycDraftFieldName.PLACE_OF_ORIGIN, "Quê quán",
            EkycDraftFieldName.BIRTH_REGISTRATION_PLACE, "Nơi đăng ký khai sinh",
            EkycDraftFieldName.ISSUE_DATE, "Ngày cấp",
            EkycDraftFieldName.EXPIRY_DATE, "Có giá trị đến"));

    /** Chữ hiển thị cho thẻ "không thời hạn", trùng với chữ ở bước quét thẻ. */
    public static final String NO_EXPIRY_TEXT = "Không thời hạn";

    private static final String CARD_TYPE_LABEL = "Loại thẻ";
    private static final int ADULT_AGE = 18;
    private static final Pattern ID_NUMBER_12 = Pattern.compile("\\d{12}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private EkycStaffIdentityRules() {
    }

    /**
     * Trường nhân viên còn phải nhập (tên JSON của bản nháp, thêm {@value #CARD_TYPE_FIELD} khi chưa biết loại thẻ),
     * theo thứ tự trên thẻ. Rỗng khi bản nháp đã đủ để duyệt.
     */
    public static List<String> missingRequiredFields(EkycIdentityDraft draft) {
        List<String> missing = new ArrayList<>();
        if (isBlank(draft.cardType())) {
            missing.add(CARD_TYPE_FIELD);
        }
        for (EkycDraftFieldName name : REQUIRED) {
            if (name == EkycDraftFieldName.EXPIRY_DATE && draft.noExpiry()) {
                continue;
            }
            if (isBlank(draft.value(name))) {
                missing.add(name.jsonName());
            }
        }
        return missing;
    }

    /**
     * Áp các trường nhân viên nhập vào bản nháp sau khi kiểm hợp lệ.
     *
     * @param values   giá trị theo trường; giá trị rỗng coi như không nhập
     * @param today    ngày hiện tại theo lịch Việt Nam
     * @throws BusinessException 400 {@code IDENTITY_REQUIRED} (thiếu trường bắt buộc), {@code EDIT_NOT_ALLOWED} (ghi đè
     *                           trường đã có), {@code INVALID_IDENTITY} (sai định dạng/loại thẻ), {@code UNDERAGE},
     *                           {@code CARD_EXPIRED}
     */
    public static EkycIdentityDraft complete(EkycIdentityDraft draft, String cardType,
                                             Map<EkycDraftFieldName, String> values, boolean noExpiry,
                                             LocalDate today) {
        String normalizedCardType = trimToNull(cardType);
        if (normalizedCardType != null && !isBlank(draft.cardType())) {
            throw editNotAllowed(CARD_TYPE_LABEL);
        }
        requireAllowedCardType(normalizedCardType);

        Map<EkycDraftFieldName, String> accepted = normalizedValues(values);
        for (EkycDraftFieldName name : accepted.keySet()) {
            if (!isBlank(draft.value(name))) {
                throw editNotAllowed(LABELS.get(name));
            }
        }
        boolean staffNoExpiry = noExpiry && !draft.noExpiry() && isBlank(draft.value(EkycDraftFieldName.EXPIRY_DATE));
        if (staffNoExpiry) {
            // Thẻ "không thời hạn": hiển thị chữ như bước quét thẻ, hồ sơ lưu id_expiry_date = null.
            accepted.put(EkycDraftFieldName.EXPIRY_DATE, NO_EXPIRY_TEXT);
        }
        requireComplete(draft, normalizedCardType, accepted, draft.noExpiry() || staffNoExpiry);
        validate(accepted, staffNoExpiry, today);
        return draft.withStaffFields(normalizedCardType, accepted, staffNoExpiry);
    }

    /**
     * Nhân viên sửa hồ sơ người dùng báo thông tin đọc từ thẻ chưa đúng: được ghi đè cả trường đã có giá trị và loại thẻ;
     * trường được sửa có nguồn {@code STAFF}. {@code noExpiry=true} đổi hạn thẻ thành "Không thời hạn"; nhập ngày hết hạn
     * thì bỏ "không thời hạn". Cùng luật hợp lệ như {@link #complete}; sau khi sửa phải đủ trường bắt buộc.
     *
     * @throws BusinessException 400 {@code IDENTITY_REQUIRED}, {@code INVALID_IDENTITY}, {@code UNDERAGE},
     *                           {@code CARD_EXPIRED}
     */
    public static EkycIdentityDraft correct(EkycIdentityDraft draft, String cardType,
                                            Map<EkycDraftFieldName, String> values, boolean noExpiry,
                                            LocalDate today) {
        String normalizedCardType = trimToNull(cardType);
        requireAllowedCardType(normalizedCardType);
        Map<EkycDraftFieldName, String> accepted = normalizedValues(values);
        boolean finalNoExpiry;
        if (noExpiry) {
            accepted.put(EkycDraftFieldName.EXPIRY_DATE, NO_EXPIRY_TEXT);
            finalNoExpiry = true;
        } else {
            // Nhập ngày hết hạn là bỏ "không thời hạn"; không đụng tới hạn thẻ thì giữ như bản nháp.
            finalNoExpiry = draft.noExpiry() && !accepted.containsKey(EkycDraftFieldName.EXPIRY_DATE);
        }
        requireComplete(draft, normalizedCardType, accepted, finalNoExpiry);
        validate(accepted, noExpiry, today);
        return draft.withStaffCorrections(normalizedCardType, accepted, finalNoExpiry);
    }

    private static void requireAllowedCardType(String cardType) {
        if (cardType != null && !ALLOWED_CARD_TYPES.contains(cardType)) {
            throw invalid("Loại thẻ không hợp lệ: FINORA chỉ nhận thẻ Căn cước công dân gắn chip, CCCD mã vạch "
                    + "hoặc thẻ Căn cước.");
        }
    }

    /** Giá trị đã cắt khoảng trắng (gộp khoảng trắng liên tiếp), theo thứ tự trường; giá trị rỗng bị bỏ. */
    private static Map<EkycDraftFieldName, String> normalizedValues(Map<EkycDraftFieldName, String> values) {
        Map<EkycDraftFieldName, String> accepted = new LinkedHashMap<>();
        for (EkycDraftFieldName name : EkycDraftFieldName.values()) {
            String value = trimToNull(values.get(name));
            if (value != null) {
                accepted.put(name, WHITESPACE.matcher(value).replaceAll(" "));
            }
        }
        return accepted;
    }

    /** Sau khi áp giá trị nhân viên nhập, mọi trường bắt buộc phải có giá trị; thiếu → 400 {@code IDENTITY_REQUIRED}. */
    private static void requireComplete(EkycIdentityDraft draft, String cardType,
                                        Map<EkycDraftFieldName, String> accepted, boolean noExpiry) {
        List<String> stillMissing = new ArrayList<>();
        if (isBlank(draft.cardType()) && cardType == null) {
            stillMissing.add(CARD_TYPE_LABEL);
        }
        for (EkycDraftFieldName name : REQUIRED) {
            boolean exempt = name == EkycDraftFieldName.EXPIRY_DATE && noExpiry;
            if (!exempt && isBlank(draft.value(name)) && !accepted.containsKey(name)) {
                stillMissing.add(LABELS.get(name));
            }
        }
        if (!stillMissing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDENTITY_REQUIRED",
                    "Vui lòng nhập đủ thông tin trên thẻ: " + String.join(", ", stillMissing) + ".");
        }
    }

    private static void validate(Map<EkycDraftFieldName, String> accepted, boolean staffNoExpiry, LocalDate today) {
        String idNumber = accepted.get(EkycDraftFieldName.ID_NUMBER);
        if (idNumber != null && !ID_NUMBER_12.matcher(idNumber).matches()) {
            throw invalid("Số định danh phải gồm đúng 12 chữ số (FINORA không nhận CMND 9 số).");
        }
        String gender = accepted.get(EkycDraftFieldName.GENDER);
        if (gender != null) {
            Gender parsed = EkycIdentityService.toGender(gender);
            if (parsed == null) {
                throw invalid("Giới tính chỉ nhận \"Nam\" hoặc \"Nữ\".");
            }
            // Chuẩn hoá Unicode để hồ sơ ghi đúng như khi đọc từ thẻ.
            accepted.put(EkycDraftFieldName.GENDER, parsed == Gender.MALE ? "Nam" : "Nữ");
        }
        LocalDate dateOfBirth = parseIfPresent(accepted, EkycDraftFieldName.DATE_OF_BIRTH);
        LocalDate issueDate = parseIfPresent(accepted, EkycDraftFieldName.ISSUE_DATE);
        LocalDate expiryDate = staffNoExpiry ? null : parseIfPresent(accepted, EkycDraftFieldName.EXPIRY_DATE);
        if (dateOfBirth != null && (dateOfBirth.isAfter(today) || dateOfBirth.plusYears(ADULT_AGE).isAfter(today))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, EkycResultCode.UNDERAGE.name(),
                    EkycMessages.result(EkycResultCode.UNDERAGE));
        }
        if (issueDate != null && (issueDate.isAfter(today) || (dateOfBirth != null && !issueDate.isAfter(dateOfBirth)))) {
            throw invalid("Ngày cấp không hợp lệ: không được ở tương lai và phải sau ngày sinh.");
        }
        if (expiryDate != null && expiryDate.isBefore(today)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, EkycResultCode.CARD_EXPIRED.name(),
                    EkycMessages.result(EkycResultCode.CARD_EXPIRED));
        }
        if (expiryDate != null && issueDate != null && !expiryDate.isAfter(issueDate)) {
            throw invalid("Ngày hết hạn phải sau ngày cấp.");
        }
    }

    private static LocalDate parseIfPresent(Map<EkycDraftFieldName, String> accepted, EkycDraftFieldName name) {
        String raw = accepted.get(name);
        if (raw == null) {
            return null;
        }
        return EkycIdentityService.parseDate(raw).orElseThrow(
                () -> invalid(LABELS.get(name) + " phải là ngày có thật, dạng dd/mm/yyyy."));
    }

    private static BusinessException editNotAllowed(String label) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "EDIT_NOT_ALLOWED",
                "Trường \"" + label + "\" đã có giá trị đọc từ thẻ, không được nhập đè.");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDENTITY", message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
