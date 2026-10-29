package com.finora.common.enums.user;

/**
 * Vai trò của người dùng trong hệ thống FINORA.
 */
public enum UserRole {
    BORROWER,
    INVESTOR,
    /** Nhân viên vận hành: quyền theo vai trò nhân viên do ADMIN cấu hình. */
    STAFF,
    ADMIN
}
