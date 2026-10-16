"""Cấu hình pytest chung cho finora-ai.

Windows: nạp zxing-cpp trước khi test credit/fraud nạp scikit-learn. scikit-learn 1.4 mang
``msvcp140.dll`` 14.32 trong ``sklearn/.libs``; nếu DLL đó vào tiến trình trước, phần ghi
mã (writer) của zxing-cpp — biên dịch bằng MSVC mới hơn — crash cả tiến trình test
(access violation). Cùng lý do ``main.py`` nạp zxing-cpp trước các router.
"""

import zxingcpp  # noqa: F401
