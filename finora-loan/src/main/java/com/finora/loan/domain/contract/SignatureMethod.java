package com.finora.loan.domain.contract;

/** Hình thức bằng chứng được provider trả về; client không có quyền tự quyết định giá trị cuối. */
public enum SignatureMethod {
    CLICK_WRAP_MVP,
    VNPT_SMART_CA
}
