"""Chọn bộ công cụ của trợ lý theo vai trò trong token và nơi gửi (mobile/web).

Một token có thể mang nhiều realm role (`KeycloakAdminServiceImpl.assignRole` thêm role mới mà
không gỡ role cũ), nên kết quả là một tập. Web chỉ dành cho quản trị (`ProtectedRoute
requiredRole="ADMIN"`), còn người vay/nhà đầu tư chỉ dùng app mobile — vì vậy cùng một người
nhưng hỏi từ hai nơi sẽ thấy hai bộ công cụ khác nhau.
"""

from enum import Enum

from app.services.auth.keycloak_jwt import Principal


class Toolset(str, Enum):
    """Bộ công cụ theo vai trò; tên trùng với giá trị trong `ASSISTANT_TOOLSETS`."""

    BORROWER = "BORROWER"
    INVESTOR = "INVESTOR"
    ADMIN = "ADMIN"


# Realm role Keycloak tương ứng từng bộ công cụ.
ROLE_OF_TOOLSET: dict[Toolset, str] = {
    Toolset.BORROWER: "ROLE_BORROWER",
    Toolset.INVESTOR: "ROLE_INVESTOR",
    Toolset.ADMIN: "ROLE_ADMIN",
}

# Bộ công cụ được phép trên từng nơi gửi.
_TOOLSETS_BY_SURFACE: dict[str, tuple[Toolset, ...]] = {
    "web": (Toolset.ADMIN,),
    "mobile": (Toolset.BORROWER, Toolset.INVESTOR),
}


def resolve_toolset(
    principal: Principal, surface: str, enabled: frozenset[Toolset]
) -> frozenset[Toolset]:
    """Tập bộ công cụ người dùng được dùng ở `surface`, sau khi giao với công tắc `enabled`.

    Tập rỗng nghĩa là vai trò không được hỗ trợ ở nơi này (hoặc đã bị tắt); router trả 403
    `ASSISTANT_ROLE_NOT_SUPPORTED`. Hàm không ném lỗi để router tự chọn mã lỗi.
    """
    allowed = _TOOLSETS_BY_SURFACE.get(surface, ())
    return frozenset(
        toolset
        for toolset in allowed
        if toolset in enabled and principal.has_role(ROLE_OF_TOOLSET[toolset])
    )
