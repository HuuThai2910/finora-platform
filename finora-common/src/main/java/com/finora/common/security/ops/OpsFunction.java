package com.finora.common.security.ops;

import com.finora.common.security.SecurityUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Danh mục chức năng quản trị để phân quyền nhân viên. Mỗi chức năng ứng với một màn của web quản trị và có tối đa hai
 * quyền: Xem ({@code ops:<code>:view}) và Thao tác ({@code ops:<code>:action}).
 *
 * <p>Quyền là client role {@value #CLIENT_ID} trên Keycloak; vai trò nhân viên là realm role composite gom các quyền
 * này, do quản trị viên tự cấu hình. Converter JWT giữ nguyên client role thành authority nên service chỉ cần kiểm
 * {@code hasAnyAuthority(...)}. {@code ROLE_ADMIN} luôn qua mọi quyền ({@link #canView()}, {@link #canAct()}), kể cả
 * khi realm chưa gắn composite cho admin.</p>
 *
 * <p>Danh mục cố định trong code vì mỗi API phải biết mình kiểm quyền nào; thêm chức năng mới là thêm hằng ở đây.
 * Cấu hình vai trò và gán vai trò không nằm trong danh mục: chỉ ADMIN làm được, để không ai tự nâng quyền.</p>
 */
public enum OpsFunction {

    DASHBOARD("dashboard", "Tổng quan & thống kê", "Tổng quan", false, false),
    LOAN_APPLICATION("loan-application", "Hồ sơ vay", "Cho vay", true, true),
    COLLECTION("collection", "Thu hồi nợ", "Cho vay", true, true),
    RESCHEDULE("reschedule", "Cơ cấu nợ", "Cho vay", true, true),
    WITHDRAWAL("withdrawal", "Rút tiền", "Vận hành", true, true),
    RECONCILIATION("reconciliation", "Đối soát & sự cố", "Vận hành", true, false),
    FUNDING("funding", "Gọi vốn & Notes", "Đầu tư", true, true),
    FUNDING_SETTINGS("funding-settings", "Cấu hình gọi vốn", "Đầu tư", true, false),
    SECONDARY_MARKET("secondary-market", "Chợ thứ cấp", "Đầu tư", true, true),
    LOAN_PRODUCT("loan-product", "Sản phẩm vay", "Sản phẩm", true, false),
    CUSTOMER("customer", "Khách hàng", "Khách hàng", true, false),
    EKYC("ekyc", "Duyệt eKYC", "Khách hàng", true, true),
    BLOCKCHAIN("blockchain", "Bằng chứng blockchain", "Hệ thống", true, false),
    AI_POLICY("ai-policy", "Chính sách AI", "AI", true, false),
    AI_SCORING("ai-scoring", "Chấm thử AI", "AI", false, false);

    /** Client Keycloak giữ các quyền {@code ops:*}. */
    public static final String CLIENT_ID = "finora-ops";
    public static final String PREFIX = "ops:";

    private final String code;
    private final String label;
    private final String group;
    private final boolean hasAction;
    private final boolean defaultStaffAction;

    OpsFunction(String code, String label, String group, boolean hasAction, boolean defaultStaffAction) {
        this.code = code;
        this.label = label;
        this.group = group;
        this.hasAction = hasAction;
        this.defaultStaffAction = defaultStaffAction;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String group() {
        return group;
    }

    public boolean hasAction() {
        return hasAction;
    }

    public String view() {
        return PREFIX + code + ":view";
    }

    public String action() {
        if (!hasAction) {
            throw new IllegalStateException("Chức năng " + code + " chỉ có quyền Xem");
        }
        return PREFIX + code + ":action";
    }

    /** Authority được Xem: quyền Xem hoặc ADMIN. Dùng cho {@code hasAnyAuthority(...)}. */
    public String[] canView() {
        return new String[] {view(), SecurityUtils.ROLE_ADMIN};
    }

    /** Authority được Thao tác: quyền Thao tác hoặc ADMIN. */
    public String[] canAct() {
        return new String[] {action(), SecurityUtils.ROLE_ADMIN};
    }

    /** Mọi quyền của danh mục, theo thứ tự hiển thị. */
    public static List<String> allPermissions() {
        List<String> permissions = new ArrayList<>();
        for (OpsFunction function : values()) {
            permissions.add(function.view());
            if (function.hasAction) {
                permissions.add(function.action());
            }
        }
        return Collections.unmodifiableList(permissions);
    }

    /** Quyền của vai trò mặc định "Nhân viên vận hành": Xem mọi chức năng, Thao tác việc vận hành hằng ngày. */
    public static List<String> defaultStaffPermissions() {
        List<String> permissions = new ArrayList<>();
        for (OpsFunction function : values()) {
            permissions.add(function.view());
            if (function.defaultStaffAction) {
                permissions.add(function.action());
            }
        }
        return Collections.unmodifiableList(permissions);
    }

    public static boolean isKnownPermission(String permission) {
        return allPermissions().contains(permission);
    }
}
