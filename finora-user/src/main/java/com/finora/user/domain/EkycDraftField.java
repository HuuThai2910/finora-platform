package com.finora.user.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * Giá trị một trường trên thẻ kèm các nguồn đã đọc ra nó ({@code QR}, {@code MRZ}, {@code OCR},
 * {@code AI}, hoặc {@code USER} khi người dùng sửa). Ngày luôn dạng {@code dd/mm/yyyy}.
 * <p>
 * Được lưu dạng JSON trong bản nháp (Redis, {@code draft_encrypted}), nên chỉ hai thành phần
 * {@code value}/{@code sources} được ghi; hàm tiện ích không được lọt vào JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EkycDraftField(String value, List<String> sources) {

    public EkycDraftField {
        sources = sources == null ? List.of() : sources.stream().filter(Objects::nonNull).toList();
    }

    /** Trường không thu thập: không có giá trị và không có nguồn nào — API trả {@code null}. */
    @JsonIgnore
    public boolean isNotCollected() {
        return value == null && sources.isEmpty();
    }
}
