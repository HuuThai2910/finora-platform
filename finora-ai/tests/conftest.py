"""Cấu hình pytest chung cho finora-ai.

Windows: nạp zxing-cpp trước khi test credit/fraud nạp scikit-learn. scikit-learn 1.4 mang
``msvcp140.dll`` 14.32 trong ``sklearn/.libs``; nếu DLL đó vào tiến trình trước, phần ghi
mã (writer) của zxing-cpp — biên dịch bằng MSVC mới hơn — crash cả tiến trình test
(access violation). Cùng lý do ``main.py`` nạp zxing-cpp trước các router.

Fixture chung: test nghiệp vụ chạy như một người gọi có đủ quyền (D35). Kiểm tra xác thực/phân quyền thật nằm ở
``tests/test_security.py``; test đó bỏ override này để đi qua JWT thật.
"""
import zxingcpp  # noqa: F401
import pytest

from app.security import CREDIT_SCORE, EKYC_OCR, FRAUD_DETECT, ROLE_ADMIN, Caller, current_caller

FULL_ACCESS = Caller(
    subject="test-caller",
    client_id="finora-test",
    authorities=frozenset({ROLE_ADMIN, CREDIT_SCORE, EKYC_OCR, FRAUD_DETECT}),
)


@pytest.fixture(autouse=True)
def full_access_caller():
    import main

    main.app.dependency_overrides[current_caller] = lambda: FULL_ACCESS
    yield FULL_ACCESS
    main.app.dependency_overrides.pop(current_caller, None)
