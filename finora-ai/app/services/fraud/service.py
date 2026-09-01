"""
Điều phối luồng chấm rủi ro gian lận giao dịch ví.

Tách khỏi router theo `.agents/rules/03-architecture-structure.md`: "Logic ML
không đặt trong router". Router chỉ lo HTTP và mã lỗi; module này lo quy tắc phạm
vi và gọi bộ dự đoán.

Ghi chú về lựa chọn thuật toán: docstring cũ của `fraud_router.py` dự kiến dùng
Isolation Forest — một thuật toán *không giám sát*, hợp lý khi chưa có nhãn.
Nhưng bộ dữ liệu huấn luyện có nhãn `isFraud` thật, và học có giám sát khai thác
nhãn tốt hơn hẳn học không giám sát. Quan trọng hơn: Isolation Forest chỉ trả về
điểm bất thường, không cho tính được precision/recall — tức là không kiểm chứng
được. Vì vậy triển khai dùng XGBoost có giám sát.
"""

from app.ml.fraud.features import LOAI_GIAO_DICH_RUI_RO
from app.ml.fraud.predictor import BoPhatHienGianLan

# Xác suất trả về cho giao dịch nằm ngoài phạm vi mô hình. Đặt đúng 0,0 thay vì
# một số nhỏ tùy ý để phía Payment phân biệt được ngay "mô hình chấm ra điểm rất
# thấp" với "mô hình không hề chấm giao dịch này".
XAC_SUAT_NGOAI_PHAM_VI = 0.0


def cham_giao_dich(bo_phat_hien: BoPhatHienGianLan, gd: dict) -> dict:
    """Chấm rủi ro một giao dịch, có kiểm tra phạm vi mô hình trước.

    Mô hình chỉ được huấn luyện trên `LOAI_GIAO_DICH_RUI_RO`. Đưa một giao dịch
    `PAYMENT` hay `CASH_IN` vào là hỏi mô hình về vùng dữ liệu nó chưa từng thấy —
    nó vẫn trả về một con số, nhưng con số đó vô nghĩa. Chặn ở đây và ghi rõ
    `da_cham_bang_mo_hinh = False` để phía gọi không nhầm im lặng thành an toàn.

    Phạm vi này phản ánh chính dữ liệu: trên 6.362.620 giao dịch PaySim, toàn bộ
    8.213 ca gian lận đều nằm trong TRANSFER và CASH_OUT; ba loại còn lại không có
    ca nào.
    """
    if gd["loai_giao_dich"] not in LOAI_GIAO_DICH_RUI_RO:
        return {
            "fraud_probability": XAC_SUAT_NGOAI_PHAM_VI,
            "muc_rui_ro": "THAP",
            "nguong_quyet_dinh": bo_phat_hien.nguong,
            "vuot_nguong": False,
            "bang_chung": [],
            "da_cham_bang_mo_hinh": False,
            "model_version": bo_phat_hien.metadata["version"],
        }

    ket_qua = bo_phat_hien.du_doan(gd)
    ket_qua["da_cham_bang_mo_hinh"] = True
    return ket_qua
