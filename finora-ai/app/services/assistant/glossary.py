"""Từ điển mã của Trợ lý FINORA: dịch mã trạng thái, mã lý do và mã lỗi sang tiếng Việt.

Vì sao cần lớp này
------------------
API nghiệp vụ trả mã kỹ thuật (`PENDING_REVIEW`, `SELF_TRADE_PREVENTED`,
`CREDIT_ASSESSMENT_NOT_LATEST`). Đưa nguyên mã cho LLM thì model tự đoán nghĩa và có
thể đoán sai — đúng điều `LAW-CONSUMER-2023` (không gây nhầm lẫn) cấm. Vì vậy code dịch
mã TRƯỚC khi gửi dữ kiện cho LLM và trước khi dựng thẻ số liệu; LLM chỉ diễn đạt lại
câu đã dịch, không tự diễn giải mã.

Nguồn của từng bảng
-------------------
- Tên mã lấy từ enum Java thật (đường dẫn ghi ở từng bảng). Test
  `tests/assistant/test_glossary.py` đọc chính các file enum đó: thêm giá trị enum mà
  quên bổ sung bảng ở đây thì test gãy, thay vì người dùng thấy "(chưa có diễn giải)".
- Câu chữ lấy từ nhãn app đang hiển thị (`finora-mobile/src/features/*/constant.ts`,
  `finora-web/src/features/*`), để người dùng thấy cùng một tên trên app và trong câu
  trả lời của trợ lý.
- Lý do chốt chặn của AI dùng lại `DIEN_GIAI_CHOT_CHAN` của `app/ml/credit/dien_giai.py`
  bằng import, không chép, để hai nơi không lệch câu chữ.

Câu chữ viết ở ngôi trung tính ("người vay", "nhà đầu tư") vì cùng một bảng phục vụ cả
ba vai trò; ngôi "bạn" do câu mẫu/LLM ở tầng trên quyết định.

Mã lạ (backend thêm mà bảng chưa có) không bị đoán nghĩa: giữ nguyên mã kèm
"(chưa có diễn giải)" để người đọc và người bảo trì cùng nhận ra.
"""

from __future__ import annotations

from typing import Literal, get_args

# Import này kéo theo rule_engine -> pandas (~1,5 giây lần đầu). Tiến trình finora-ai đã
# nạp sẵn chúng cho API chấm điểm nên không tốn thêm RAM; đổi lấy việc chỉ có MỘT nguồn
# câu chữ cho lý do chốt chặn.
from app.ml.credit.dien_giai import DIEN_GIAI_CHOT_CHAN

NhomTrangThai = Literal[
    "application",
    "contract",
    "loan",
    "funding",
    "terms",
    "listing",
    "commitment",
    "note",
    "book_order",
    "primary_order",
    "collection",
    "reschedule",
    "disbursement",
    "pin",
    "ekyc",
]

CHUA_CO_DIEN_GIAI = "(chưa có diễn giải)"

# Giá trị trống hiển thị giống `formatting.tien_vnd(None)` để thẻ số liệu đồng nhất.
KHONG_CO = "—"

# Mỗi trạng thái: (nhãn ngắn cho thẻ số liệu, câu giải thích cho LLM/câu mẫu).
_TrangThai = tuple[str, str]

# finora-loan/.../domain/application/LoanApplicationStatus.java
_HO_SO: dict[str, _TrangThai] = {
    "SUBMITTED": ("Đã nộp", "FINORA đã nhận hồ sơ; bước tiếp theo là kiểm tra điều kiện vay."),
    "ELIGIBILITY_PENDING": (
        "Đang kiểm tra điều kiện",
        "Hệ thống đang đối chiếu hồ sơ với điều kiện của sản phẩm vay (độ tuổi, xác minh danh tính).",
    ),
    "SCORING": (
        "Đang chấm điểm",
        "Mô hình tín dụng đang đánh giá hồ sơ; việc này thường mất vài phút.",
    ),
    "SCORING_RETRY_PENDING": (
        "Chờ chấm điểm lại",
        "Lần chấm điểm trước chưa hoàn tất nên hệ thống sẽ tự chạy lại.",
    ),
    "PENDING_REVIEW": (
        "Chờ thẩm định",
        "Chuyên viên FINORA đang xem hồ sơ. Đây là bước bình thường, không phải lỗi.",
    ),
    "APPROVED": (
        "Đã duyệt",
        "Hồ sơ được duyệt, nghĩa là được xác nhận điều khoản và gọi vốn từ nhà đầu tư. "
        "Đã duyệt chưa phải là đã giải ngân: tiền chỉ được chuyển khi gọi đủ vốn và các bên ký hợp đồng.",
    ),
    "REJECTED": (
        "Không được duyệt",
        "Hồ sơ này không được duyệt. Người vay có thể nộp hồ sơ mới khi điều kiện thay đổi.",
    ),
    "WITHDRAWN": ("Đã rút", "Người vay đã rút hồ sơ nên FINORA dừng xử lý."),
}

# finora-loan/.../domain/contract/LoanContractStatus.java
_HOP_DONG: dict[str, _TrangThai] = {
    "PENDING_LENDER_SIGNATURES": (
        "Chờ nhà đầu tư ký",
        "Khoản vay đã đủ vốn; các nhà đầu tư đang ký cùng một bản hợp đồng. "
        "Đủ chữ ký nhà đầu tư thì mới tới lượt người vay ký.",
    ),
    "PENDING_BORROWER_SIGNATURE": (
        "Chờ người vay ký",
        "Mọi nhà đầu tư đã ký; hợp đồng đang chờ người vay đọc và ký trước hạn.",
    ),
    "PENDING_SIGNATURE": (
        "Chờ người vay ký",
        "Hợp đồng đã chốt điều khoản và đang chờ người vay xác nhận trước hạn.",
    ),
    "SIGNING": (
        "Đang chờ xác nhận SmartCA",
        "Yêu cầu ký đã gửi tới VNPT SmartCA và đang chờ người ký xác nhận trên ứng dụng SmartCA.",
    ),
    "SIGNED": ("Đã ký", "FINORA đã ghi nhận chữ ký của người vay; hợp đồng sắp có hiệu lực."),
    "DECLINED": ("Đã từ chối", "Người vay đã từ chối hợp đồng nên khoản vay không được thiết lập."),
    "EXPIRED": ("Đã hết hạn ký", "Hợp đồng quá hạn ký nên không còn ký được."),
    "EFFECTIVE": (
        "Đang hiệu lực",
        "Hợp đồng đã đủ chữ ký các bên và có hiệu lực; bước tiếp theo là giải ngân.",
    ),
    "COMPLETED": ("Đã hoàn tất", "Khoản vay theo hợp đồng này đã kết thúc."),
}

# finora-loan/.../domain/servicing/FinoraLoanStatus.java
_KHOAN_VAY: dict[str, _TrangThai] = {
    "ACTIVE": ("Đang trả nợ", "Khoản vay đã giải ngân và đang trong thời gian trả góp."),
    "RESTRUCTURING": (
        "Đang cơ cấu",
        "Lịch cơ cấu đã được duyệt và đang được áp dụng; thanh toán tạm khóa cho tới khi lịch mới "
        "có hiệu lực.",
    ),
    "SETTLED": ("Đã tất toán", "Khoản vay đã trả hết mọi nghĩa vụ và kết thúc."),
    "DEFAULTED": (
        "Nợ xấu",
        "Khoản vay quá hạn từ 91 ngày (nhóm nợ 3 trở lên). Trạng thái này giữ tới khi trả hết phần "
        "quá hạn; không phát sinh phí nợ xấu riêng.",
    ),
    "WRITTEN_OFF": (
        "Đã xử lý rủi ro",
        "Khoản vay đã được xử lý rủi ro; ứng dụng chỉ còn cho xem và đối chiếu số liệu.",
    ),
}

# finora-loan/.../domain/application/LoanFundingStatus.java
_GOI_VON: dict[str, _TrangThai] = {
    "REQUESTED": (
        "Đang gọi vốn",
        "Hồ sơ đã lên sàn và đang chờ nhà đầu tư góp đủ vốn.",
    ),
    "FULLY_FUNDED": (
        "Đã đủ vốn",
        "Nhà đầu tư đã góp đủ vốn; FINORA lập hợp đồng chung để các bên ký.",
    ),
    "EXPIRED": (
        "Gọi vốn không thành công",
        "Hết thời hạn gọi vốn mà chưa đủ vốn nên không lập hợp đồng cho đợt này; tiền giữ của "
        "nhà đầu tư được trả lại ví.",
    ),
    "CANCELLED": (
        "Đã hủy sau khi đủ vốn",
        "Khoản vay đã đủ vốn nhưng sẽ không được giải ngân (hợp đồng bị từ chối hoặc hết hạn ký, hoặc giải "
        "ngân thất bại khi tiền chưa chuyển); phần vốn của nhà đầu tư bị hủy và tiền đang giữ được trả lại ví.",
    ),
}

# finora-loan/.../domain/application/TermsConfirmationStatus.java
_DIEU_KHOAN: dict[str, _TrangThai] = {
    "AUTO_AUTHORIZED": (
        "Tự tiếp tục",
        "Điều khoản cuối không bất lợi hơn lúc nộp và người vay đã đồng ý cơ chế tự tiếp tục khi nộp hồ sơ.",
    ),
    "PENDING": (
        "Chờ người vay xác nhận",
        "Điều khoản cuối bất lợi hơn lúc nộp (lãi, phí hoặc số phải trả tăng) nên FINORA phải chờ người "
        "vay chấp nhận hoặc từ chối trước khi lập hợp đồng.",
    ),
    "ACCEPTED": ("Đã chấp nhận", "Người vay đã chấp nhận điều khoản cuối."),
    "DECLINED": (
        "Đã từ chối điều khoản",
        "Người vay không chấp nhận điều khoản cuối nên FINORA không lập hợp đồng.",
    ),
    "EXPIRED": (
        "Hết hạn phản hồi",
        "Người vay chưa xác nhận điều khoản trong thời hạn nên FINORA không lập hợp đồng.",
    ),
}

# finora-common/.../enums/investment/ListingStatus.java
_SAN: dict[str, _TrangThai] = {
    "DRAFT": (
        "Chờ quản trị duyệt",
        "Khoản vay đã về sàn nhưng quản trị chưa đặt mệnh giá Note và duyệt, nên nhà đầu tư chưa thấy.",
    ),
    "OPEN": ("Đang gọi vốn", "Nhà đầu tư có thể góp vốn cho tới hạn đóng gọi vốn."),
    "FULLY_FUNDED": ("Đã đủ vốn", "Tổng cam kết đã chạm mục tiêu; khoản vay không nhận thêm lệnh."),
    "CLOSED": (
        "Đã đóng",
        "Đợt gọi vốn đã đóng (thường do hết hạn mà chưa gọi đủ vốn); khoản vay không nhận thêm lệnh.",
    ),
    "CANCELLED": ("Đã hủy", "Khoản vay đã bị rút khỏi sàn; không nhận thêm lệnh."),
}

# finora-common/.../enums/investment/CommitmentStatus.java
_PHAN_VON: dict[str, _TrangThai] = {
    "ACTIVE": (
        "Đang giữ chỗ",
        "Phần vốn đã được giữ tiền trong ví và tính vào tổng gọi vốn; vẫn hủy được trước khi khóa.",
    ),
    "FINALIZED": (
        "Đã khóa vốn",
        "Phần vốn đã bị khóa cho giải ngân, không hủy được nữa; Note được phát hành khi giải ngân xong.",
    ),
    "CANCELLED": ("Đã hủy", "Phần vốn đã hủy và tiền giữ đã trả lại ví."),
}

# finora-common/.../enums/investment/NoteStatus.java
_NOTE: dict[str, _TrangThai] = {
    "ACTIVE": (
        "Đang hoạt động",
        "Note đã phát hành và còn dư nợ gốc; nhận gốc lãi theo lịch trả của người vay.",
    ),
    "CLOSED": ("Đã tất toán", "Note đã thu hồi hết gốc."),
    "DEFAULTED": (
        "Nợ xấu",
        "Khoản vay đã chuyển nợ xấu nên Note ngừng sinh dòng tiền kỳ vọng cho tới khi người vay khắc phục.",
    ),
}

# finora-investment/.../domain/orderbook/BookOrderStatus.java (chợ Notes, sổ lệnh)
_LENH_SO: dict[str, _TrangThai] = {
    "PENDING_FUNDS": ("Đang giữ tiền", "Lệnh mua đang chờ ví xác nhận giữ tiền rồi mới vào sổ."),
    "OPEN": ("Đang chờ khớp", "Lệnh đã nằm trong sổ, chờ lệnh đối ứng có giá phù hợp."),
    "PARTIALLY_FILLED": ("Khớp một phần", "Một phần lệnh đã khớp; phần còn lại vẫn nằm trong sổ."),
    "FILLED": ("Đã khớp hết", "Toàn bộ khối lượng của lệnh đã khớp."),
    "CANCELLED": ("Đã hủy", "Phần chưa khớp của lệnh đã bị hủy; lý do nằm ở mã hủy của lệnh."),
    "REJECTED": (
        "Bị từ chối",
        "Lệnh không vào được sổ, thường vì ví không giữ được tiền; tiền giữ (nếu có) được trả lại ví.",
    ),
}

# finora-common/.../enums/investment/OrderStatus.java (lệnh góp vốn sơ cấp)
_LENH_GOP_VON: dict[str, _TrangThai] = {
    "PENDING_FUNDS": ("Đang giữ tiền", "Lệnh góp vốn đã tạo và đang chờ ví giữ tiền."),
    "COMMITTED": ("Đã góp vốn", "Ví đã giữ tiền và phần vốn đã được tính vào khoản vay."),
    "REJECTED": ("Bị từ chối", "Lệnh góp vốn không thành công; tiền giữ (nếu có) được trả lại ví."),
    "CANCELLED": (
        "Đã hủy",
        "Lệnh góp vốn đã hủy (nhà đầu tư tự hủy hoặc khoản vay hết hạn gọi vốn); tiền giữ được trả lại ví.",
    ),
}

# finora-loan/.../domain/collection/CollectionStage.java + CollectionCaseStatus.java.
# Hai enum không trùng giá trị nào nên chung một nhóm "collection".
_THU_HOI: dict[str, _TrangThai] = {
    "EARLY_REMINDER": ("Nhắc sớm", "Quá hạn 1 đến 9 ngày (nhóm nợ 1); FINORA nhắc nợ."),
    "ATTENTION": ("Cần chú ý", "Quá hạn 10 đến 90 ngày (nhóm nợ 2); nhắc nợ tăng cường."),
    "NPL": ("Nợ xấu", "Quá hạn 91 đến 180 ngày (nhóm nợ 3); được xem là nợ xấu nội bộ."),
    "INTENSIVE": ("Thu hồi tăng cường", "Quá hạn 181 đến 360 ngày (nhóm nợ 4); nợ xấu nội bộ."),
    "LOSS": ("Có nguy cơ mất vốn", "Quá hạn trên 360 ngày (nhóm nợ 5); nợ xấu, thu hồi đặc biệt."),
    "OPEN": ("Đang theo dõi thu hồi", "Hồ sơ thu hồi đang mở vì khoản vay còn quá hạn."),
    "CURED": ("Đã khắc phục", "Người vay đã trả hết phần quá hạn nên hồ sơ thu hồi đóng lại."),
    "SETTLED": ("Đã tất toán", "Khoản vay đã trả hết nên hồ sơ thu hồi đóng lại."),
    "WRITTEN_OFF": ("Đã xử lý rủi ro", "Khoản vay đã được xử lý rủi ro; hồ sơ thu hồi kết thúc."),
}

# finora-loan/.../domain/restructure/LoanRescheduleStatus.java. Bốn bước tạo/phê duyệt lịch
# trên hệ thống lõi là việc nội bộ nên người dùng chỉ cần biết "đang áp dụng lịch mới".
_CO_CAU: dict[str, _TrangThai] = {
    "PENDING_REVIEW": ("Chờ thẩm định", "Yêu cầu cơ cấu đang chờ chuyên viên xem xét."),
    "PENDING_INVESTOR_CONSENT": (
        "Chờ nhà đầu tư đồng ý",
        "Quản trị đã duyệt yêu cầu cơ cấu; các nhà đầu tư đang nắm Note của khoản vay đang cho ý kiến. Lịch "
        "trả mới chỉ được áp dụng khi tất cả cùng đồng ý.",
    ),
    "INVESTOR_REJECTED": (
        "Nhà đầu tư không đồng ý",
        "Không phải mọi nhà đầu tư đang nắm Note đều đồng ý nên yêu cầu cơ cấu không được áp dụng; lịch trả "
        "cũ giữ nguyên.",
    ),
    "REJECTED": ("Đã từ chối", "Yêu cầu cơ cấu không được duyệt; lịch trả cũ giữ nguyên."),
    "CREATE_PENDING": ("Đã duyệt, chờ áp dụng", "Yêu cầu đã được duyệt; hệ thống sắp tạo lịch trả mới."),
    "CREATING": ("Đang áp dụng lịch mới", "Hệ thống lõi đang tạo lịch trả mới."),
    "APPROVAL_PENDING": ("Đang áp dụng lịch mới", "Lịch mới đã tạo, đang chờ hệ thống lõi phê duyệt."),
    "APPROVING": ("Đang áp dụng lịch mới", "Hệ thống lõi đang phê duyệt lịch trả mới."),
    "COMPLETED": ("Đã áp dụng", "Lịch trả mới đã có hiệu lực."),
    "RECONCILIATION_REQUIRED": (
        "Đang đối soát",
        "Kết quả từ hệ thống lõi chưa khớp nên quản trị đang đối soát trước khi áp dụng.",
    ),
    "MANUAL_REVIEW": ("Đang kiểm tra thêm", "Yêu cầu cần quản trị kiểm tra thủ công trước khi tiếp tục."),
}

# finora-loan/.../domain/disbursement/DisbursementSagaStatus.java (chủ yếu cho quản trị)
_GIAI_NGAN: dict[str, _TrangThai] = {
    "WAITING_PAYMENT": (
        "Chờ chuyển tiền",
        "Đang chờ ví chuyển tiền từ phần giữ của nhà đầu tư sang người vay.",
    ),
    "CORE_BOOKING_PENDING": (
        "Chờ ghi sổ hệ thống lõi",
        "Tiền đã chuyển; đang chờ ghi khoản vay vào hệ thống lõi (Fineract).",
    ),
    "CORE_BOOKING": ("Đang ghi sổ hệ thống lõi", "Hệ thống lõi (Fineract) đang ghi khoản vay."),
    "RETRY_PENDING": ("Chờ thử lại", "Bước ghi sổ gặp lỗi tạm thời; hệ thống sẽ tự thử lại."),
    "REPAIR_REQUIRED": (
        "Cần xử lý tay",
        "Giải ngân dừng ở bước ghi sổ và cần quản trị kiểm tra, xử lý thủ công.",
    ),
    "COMPLETED": ("Đã giải ngân", "Tiền đã chuyển và khoản vay đã ghi sổ xong."),
    "PAYMENT_FAILED": ("Chuyển tiền thất bại", "Ví không chuyển được tiền giải ngân."),
}

# Không có enum Java: tool `trang_thai_pin` suy ra từ PinStatusResponse(hasPin, locked)
# của finora-user — NOT_SET khi hasPin=false, LOCKED khi locked=true, còn lại ACTIVE.
_PIN: dict[str, _TrangThai] = {
    "NOT_SET": (
        "Chưa tạo mã PIN",
        "Tài khoản chưa có mã PIN giao dịch; cần tạo PIN trước khi thanh toán, đầu tư hoặc ký hợp đồng.",
    ),
    "ACTIVE": ("Đã có mã PIN", "Mã PIN giao dịch đang dùng được."),
    "LOCKED": (
        "Mã PIN đang tạm khóa",
        "Mã PIN bị khóa 15 phút do nhập sai 5 lần liên tiếp; có thể chờ hết thời gian hoặc dùng Quên mã PIN.",
    ),
}

# finora-common/.../enums/user/EkycStatus.java (trạng thái hồ sơ) + EkycResultCode.java
# (kết quả lần quét vừa rồi). VERIFIED có ở cả hai enum với cùng nghĩa.
_EKYC: dict[str, _TrangThai] = {
    "PENDING": ("Chưa xác minh", "Người dùng chưa hoàn tất xác minh danh tính (eKYC)."),
    "VERIFIED": ("Đã xác minh", "Danh tính đã được xác minh bằng CCCD."),
    "FAILED": ("Xác minh không thành công", "Lần xác minh danh tính không thành công; có thể làm lại."),
    "MANUAL_REVIEW": ("Chờ duyệt tay", "Hồ sơ xác minh danh tính đang chờ quản trị kiểm tra thủ công."),
    "DRAFT_READY": (
        "Chờ xác nhận thông tin",
        "Đã đọc xong thông tin trên CCCD; người dùng cần soát lại và xác nhận thì hồ sơ mới được lưu.",
    ),
    "DRAFT_EXPIRED": ("Phiên xác minh hết hạn", "Phiên xác minh đã hết hạn; cần quét lại CCCD từ đầu."),
    "OCR_FAILED": (
        "Không đọc được CCCD",
        "Không đọc được số CCCD trên ảnh mặt trước; cần chụp lại rõ, đủ sáng, không lóa.",
    ),
    "ID_MISMATCH": ("CCCD không khớp hồ sơ", "Số CCCD trên ảnh khác số CCCD đã có trong hồ sơ."),
    "ID_TAKEN": (
        "CCCD đã được đăng ký",
        "Số CCCD này đã thuộc một tài khoản khác; mỗi CCCD chỉ dùng cho một tài khoản.",
    ),
    "RATE_LIMITED": ("Thao tác quá nhanh", "Gửi xác minh quá nhanh; chờ ít phút rồi thử lại."),
    "AI_UNAVAILABLE": (
        "Dịch vụ xác minh đang bận",
        "Dịch vụ đọc CCCD tạm thời không phản hồi; trạng thái hồ sơ giữ nguyên, thử lại sau.",
    ),
}

_BANG_TRANG_THAI: dict[str, dict[str, _TrangThai]] = {
    "application": _HO_SO,
    "contract": _HOP_DONG,
    "loan": _KHOAN_VAY,
    "funding": _GOI_VON,
    "terms": _DIEU_KHOAN,
    "listing": _SAN,
    "commitment": _PHAN_VON,
    "note": _NOTE,
    "book_order": _LENH_SO,
    "primary_order": _LENH_GOP_VON,
    "collection": _THU_HOI,
    "reschedule": _CO_CAU,
    "disbursement": _GIAI_NGAN,
    "pin": _PIN,
    "ekyc": _EKYC,
}

# `DIEN_GIAI_CHOT_CHAN["TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT"]` gọi trần 400 triệu là quy định của
# Quyết định 2866/QĐ-NHNN, trong khi LEGAL-COMPLIANCE.md (`LEGAL-LIMIT-01`, `LEGAL-OPEN-02`)
# ghi rõ chưa có bản gốc và "không được gọi là constraint pháp lý đã xác minh". Trợ lý phải
# tách LUẬT / CHÍNH SÁCH / CHƯA XÁC MINH, nên riêng câu này được ghi đè; các câu còn lại
# dùng nguyên văn từ dien_giai.py.
_GHI_DE_CHOT_CHAN: dict[str, str] = {
    "TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT": (
        "Tổng dư nợ vay ngang hàng cộng khoản vay này vượt giới hạn dư nợ FINORA đang áp dụng. "
        "Mức giới hạn này hiện là chính sách sản phẩm; nguồn pháp lý gốc đang chờ đối chiếu."
    ),
}

# Mã lý do: lịch sử hồ sơ (`reasonCode` trong /history), quyết định của AI và admin, lý do
# hủy lệnh trên sổ. Không trùng khóa với _MA_LOI (test kiểm).
_LY_DO: dict[str, str] = {
    **DIEN_GIAI_CHOT_CHAN,
    **_GHI_DE_CHOT_CHAN,
    # Lý do cần thẩm định của AI (app/services/credit/rule_engine.py)
    "CIC_BAD_DEBT_RECOVERY_REVIEW": (
        "Người vay đã khắc phục nợ xấu và đang trong giai đoạn phục hồi, nên hồ sơ cần chuyên viên "
        "thẩm định trước khi quyết định."
    ),
    "CIC_CURRENT_GROUP_MISSING": (
        "CIC có lịch sử tín dụng nhưng chưa cung cấp nhóm nợ hiện tại, nên hệ thống không tự động duyệt."
    ),
    # Mốc lịch sử hồ sơ (LoanApplicationServiceImpl, LoanApplicationSubmissionStateService,
    # CreditScoringStateService, CreditScoringAdminStateService)
    "APPLICATION_SUBMITTED": "Người vay đã nộp hồ sơ.",
    "APPLICATION_WITHDRAWN": "Người vay đã rút hồ sơ.",
    "ELIGIBILITY_CHECK_STARTED": "Hệ thống bắt đầu kiểm tra điều kiện vay.",
    "CREDIT_SCORING_STARTED": "Hệ thống bắt đầu chấm điểm tín dụng.",
    "CREDIT_SCORING_RETRY_STARTED": "Hệ thống chấm điểm lại sau lần chưa thành công.",
    "CREDIT_SCORING_MANUAL_RETRY_REQUESTED": "Chuyên viên yêu cầu chấm điểm lại hồ sơ.",
    "CREDIT_SCORING_MANUAL_REVIEW_REQUIRED": (
        "Chấm điểm tự động không hoàn tất sau các lần thử nên hồ sơ chuyển sang chuyên viên thẩm định."
    ),
    "AI_POLICY_AUTO_APPROVED": "Hồ sơ đạt ngưỡng tự duyệt theo chính sách chấm điểm của FINORA.",
    "AI_POLICY_REQUIRES_REVIEW": (
        "Điểm hồ sơ nằm trong vùng cần chuyên viên thẩm định theo chính sách chấm điểm của FINORA."
    ),
    "AI_POLICY_AUTO_REJECTED": (
        "Hồ sơ không đạt ngưỡng hoặc vướng điều kiện bắt buộc theo chính sách chấm điểm của FINORA."
    ),
    # Kiểm tra điều kiện trước khi chấm (BorrowerEligibilityPolicy.java)
    "BORROWER_AGE_MISSING": (
        "Hồ sơ người dùng chưa có ngày sinh nên chưa xác định được tuổi; chuyên viên sẽ kiểm tra thêm."
    ),
    "KYC_NOT_COMPLETED": "Xác minh danh tính (eKYC) chưa hoàn tất; hồ sơ chờ tới khi eKYC xong.",
    "KYC_NOT_VALID": "Xác minh danh tính (eKYC) bị từ chối hoặc đã hết hạn nên hồ sơ không đủ điều kiện.",
    "KYC_STATUS_UNSUPPORTED": "Trạng thái xác minh danh tính chưa rõ ràng; chuyên viên sẽ kiểm tra thêm.",
    "BORROWER_AGE_OUT_OF_POLICY": "Tuổi người vay nằm ngoài độ tuổi sản phẩm cho phép.",
    # Lỗi khi gọi dịch vụ chấm điểm (AiCreditScoringHttpClient.java) — ghi vào lịch sử khi
    # hồ sơ chuyển SCORING_RETRY_PENDING.
    "AI_UNAVAILABLE": "Chưa kết nối được dịch vụ chấm điểm; hệ thống sẽ tự thử lại.",
    "AI_TIMEOUT": "Dịch vụ chấm điểm phản hồi quá lâu; hệ thống sẽ tự thử lại.",
    "AI_TEMPORARY_ERROR": "Dịch vụ chấm điểm lỗi tạm thời; hệ thống sẽ tự thử lại.",
    "AI_CIRCUIT_OPEN": "Tạm dừng gọi dịch vụ chấm điểm vì đang lỗi liên tiếp; hệ thống sẽ thử lại sau.",
    "AI_REQUEST_REJECTED": "Dịch vụ chấm điểm không nhận dữ liệu hồ sơ; chuyên viên sẽ thẩm định thủ công.",
    "AI_UNEXPECTED_ERROR": "Lỗi không xác định khi chấm điểm; chuyên viên sẽ thẩm định thủ công.",
    "AI_MODEL_VERSION_MISMATCH": (
        "Phiên bản mô hình chấm điểm không khớp cấu hình; chuyên viên sẽ thẩm định thủ công."
    ),
    "AI_CONTRACT_MISMATCH": "Kết quả chấm điểm sai khuôn dữ liệu; chuyên viên sẽ thẩm định thủ công.",
    # Quyết định của chuyên viên (domain/decision/AdminDecisionReasonCode.java)
    "POLICY_APPROVED": "Chuyên viên duyệt vì hồ sơ đáp ứng chính sách cho vay.",
    "INSUFFICIENT_REPAYMENT_CAPACITY": "Khả năng trả nợ chưa đạt.",
    "IDENTITY_OR_KYC_NOT_ELIGIBLE": "Thông tin định danh hoặc eKYC không đủ điều kiện.",
    "INCONSISTENT_DECLARED_INFORMATION": "Thông tin khai báo không nhất quán.",
    "POLICY_NOT_SATISFIED": "Hồ sơ không đạt chính sách cho vay.",
    "OTHER_MANUAL_REVIEW": "Lý do thẩm định khác; chuyên viên ghi chi tiết riêng.",
    # Lịch sử hợp đồng (LoanContractStateService, InvestorContractServiceImpl, ...)
    "FULLY_FUNDED_CONTRACT_CREATED": "Khoản vay đủ vốn nên FINORA lập hợp đồng chung cho các bên.",
    "ALL_LENDERS_SIGNED": "Mọi nhà đầu tư đã ký; tới lượt người vay ký.",
    "CONTRACT_SIGNED": "Người vay đã ký hợp đồng.",
    "ALL_PARTIES_SIGNED": "Đủ chữ ký các bên; hợp đồng có hiệu lực.",
    "SIGNATURE_WINDOW_EXPIRED": "Hết hạn ký mà chưa đủ chữ ký nên hợp đồng không còn ký được.",
    # Lý do người vay từ chối hợp đồng (DECLINE_REASONS của app mobile)
    "TERMS_NOT_ACCEPTED": "Người vay không đồng ý điều khoản.",
    "BORROWER_CHANGED_MIND": "Người vay thay đổi nhu cầu vay.",
    # Lý do hủy lệnh trên sổ (finora-investment/.../domain/orderbook/CancelReason.java)
    "USER": "Người đặt lệnh tự hủy phần chưa khớp.",
    "SELF_TRADE_PREVENTED": (
        "Lệnh chạm vào lệnh đối ứng của chính người đặt nên phần còn lại bị hủy, để không ai tự mua "
        "bán với chính mình."
    ),
    "NOTE_UNAVAILABLE": "Note trong lệnh bán đã tất toán hoặc đổi chủ nên phần còn lại bị hủy.",
    "DEFAULT_NOT_ACKNOWLEDGED": (
        "Khoản vay đang nợ xấu; lệnh mua phải xác nhận đã đọc cảnh báo nợ xấu, lệnh chưa xác nhận "
        "không được khớp và bị hủy."
    ),
    "BOOK_CLOSED": "Mọi Note của khoản vay đã tất toán nên sổ lệnh đóng và lệnh còn lại bị hủy.",
}

# Mã lỗi API thường gặp (trường `code` của lỗi JSON, `rejectedReasonCode`/`rejectReasonCode`
# của lệnh, mã bỏ qua của Auto-Invest). Nhóm theo service để dễ đối chiếu với code Java.
_MA_LOI: dict[str, str] = {
    # finora-user TransactionPinService + finora-common PinTokenService/RequirePinInterceptor
    "PIN_REQUIRED": "Thao tác này cần nhập mã PIN giao dịch trước.",
    "PIN_TOKEN_INVALID": (
        "Phiên xác nhận PIN đã hết hạn hoặc không đúng thao tác; bấm lại thao tác và nhập lại PIN."
    ),
    "PIN_NOT_CONFIGURED": (
        "Máy chủ chưa cấu hình xác nhận PIN; đây là lỗi hệ thống, không phải lỗi người dùng."
    ),
    "PIN_NOT_SET": "Tài khoản chưa tạo mã PIN giao dịch.",
    "PIN_INCORRECT": "Mã PIN không đúng; nhập sai 5 lần liên tiếp thì PIN bị khóa 15 phút.",
    "PIN_LOCKED": (
        "Mã PIN đang tạm khóa 15 phút do nhập sai nhiều lần; chờ hết thời gian hoặc dùng Quên mã PIN."
    ),
    "PIN_TOO_WEAK": "Mã PIN quá dễ đoán (dãy số liên tiếp hoặc lặp lại); hãy chọn mã khác.",
    "PIN_ALREADY_SET": "Tài khoản đã có mã PIN; dùng chức năng đổi mã PIN.",
    "PIN_UNCHANGED": "Mã PIN mới phải khác mã PIN hiện tại.",
    "PASSWORD_INCORRECT": "Mật khẩu đăng nhập không đúng.",
    "PASSWORD_ATTEMPTS_EXCEEDED": "Tài khoản tạm khóa do nhập sai mật khẩu nhiều lần; thử lại sau 15 phút.",
    # finora-investment InvestorEligibility
    "EKYC_REQUIRED": (
        "Cần hoàn tất xác minh danh tính (eKYC) trước khi đầu tư; nếu vừa xác minh xong, hãy đăng nhập lại."
    ),
    "SELF_FUNDING_FORBIDDEN": "Không thể đầu tư vào khoản vay của chính mình.",
    # finora-investment: gọi vốn sơ cấp (OrderTransactionServiceImpl, MarketListingServiceImpl)
    "LISTING_NOT_FOUND": "Không tìm thấy khoản vay trên sàn.",
    "LISTING_NOT_OPEN": "Khoản vay không còn nhận vốn.",
    "LISTING_WINDOW_CLOSED": "Đã hết hạn gọi vốn cho khoản vay này.",
    "LISTING_INSUFFICIENT_REMAINING": "Số tiền đặt lớn hơn phần vốn khoản vay còn thiếu.",
    "LISTING_EXPIRED": (
        "Khoản vay hết hạn gọi vốn mà chưa đủ vốn; lệnh bị hủy và tiền giữ được trả lại ví."
    ),
    "FUNDING_WINDOW_EXPIRED": "Đợt gọi vốn đã hết hạn mà chưa đủ vốn.",
    "ORDER_BELOW_MINIMUM": "Số tiền đầu tư thấp hơn mức tối thiểu của khoản vay.",
    "ORDER_NOT_DIVISIBLE": "Số tiền đầu tư phải chia hết cho mệnh giá Note.",
    "ORDER_NOT_FOUND": "Không tìm thấy lệnh.",
    "COMMITMENT_NOT_FOUND": "Lệnh chưa có phần vốn để hủy.",
    "COMMITMENT_ALREADY_FINALIZED": "Phần vốn đã khóa cho giải ngân nên không hủy được.",
    "FUNDED_ALLOCATION_LOCKED": (
        "Khoản vay đã đủ vốn, danh sách phân bổ đã khóa để lập hợp đồng nên không hủy được."
    ),
    "NO_FINALIZED_COMMITMENT": "Khoản vay chưa có phần vốn nào đã khóa để phát hành Note.",
    "IDEMPOTENCY_KEY_REUSED": (
        "Yêu cầu trùng mã chống gửi hai lần nhưng khác nội dung; tải lại rồi thao tác lại."
    ),
    "IDEMPOTENCY_KEY_REQUIRED": (
        "Thiếu mã chống gửi hai lần; đây là lỗi của ứng dụng, hãy thử lại hoặc cập nhật app."
    ),
    "ACCESS_DENIED": "Không có quyền thao tác trên mục này.",
    # finora-investment: chợ Notes, sổ lệnh (OrderBookPricing, OrderBookTransactionService, BookOrder)
    "ORDER_PRICE_TICK": "Giá chỉ được đặt theo bước 0,1%.",
    "ORDER_PRICE_OUT_OF_RANGE": "Giá phải từ 0,1% tới 100% dư nợ gốc còn lại.",
    "ORDER_ALREADY_FILLED": "Lệnh đã khớp hết nên không còn gì để hủy.",
    "ORDER_NOT_CANCELLABLE": "Lệnh chưa hủy được lúc này (đang chờ giữ tiền hoặc đã bị từ chối).",
    "ORDER_INVALID_STATE": "Lệnh không còn ở trạng thái cho phép thao tác này.",
    "ORDER_BOOK_NOT_FOUND": "Khoản vay này chưa có sổ lệnh.",
    "BOOK_NOT_TRADABLE": "Khoản vay chưa có Note nào đang lưu hành để mua.",
    "INSUFFICIENT_FREE_NOTES": "Số Note muốn bán nhiều hơn số Note chưa nằm trong lệnh bán nào.",
    "PAYMENT_TIMEOUT": "Ví không xác nhận giữ tiền kịp thời nên lệnh bị từ chối; tiền giữ được trả lại ví.",
    "BID_HOLD_MISSING": "Lệnh mua không có khoản tiền giữ tương ứng; cần đối soát.",
    # finora-investment AutoInvestMatcher: mã bỏ qua ghi vào nhật ký Auto-Invest
    "ALREADY_INVESTED": "Đã có lệnh góp vốn vào khoản vay này nên Auto-Invest bỏ qua.",
    "BELOW_MINIMUM": (
        "Mức tiền mỗi khoản đã cài thấp hơn mức đầu tư tối thiểu của khoản vay nên Auto-Invest bỏ qua."
    ),
    "INSUFFICIENT_FUNDS": "Ví không đủ tiền khả dụng nên Auto-Invest không đặt được lệnh.",
    "LISTING_FULL": "Khoản vay đã đủ vốn trước khi tới lượt nên Auto-Invest bỏ qua.",
    "LISTING_CLOSED": "Khoản vay đã đóng gọi vốn nên Auto-Invest bỏ qua.",
    "ORDER_REJECTED": "Lệnh tự động bị từ chối.",
    "ORDER_CANCELLED": "Lệnh tự động đã bị hủy.",
    "ORDER_PENDING_FUNDS": "Lệnh tự động chưa giữ được tiền.",
    # finora-payment (ví, giữ tiền, trả nợ, nạp tiền)
    "PAYMENT_INSUFFICIENT_BALANCE": "Số dư ví không đủ cho giao dịch.",
    "INSUFFICIENT_BALANCE": "Số dư ví không đủ cho giao dịch.",
    "PAYMENT_WALLET_NOT_ACTIVE": "Ví không ở trạng thái hoạt động.",
    "PAYMENT_WALLET_NOT_FOUND": "Không tìm thấy ví.",
    "PAYMENT_REJECTED": "Ví từ chối giao dịch.",
    "PAYMENT_UNAVAILABLE": "Dịch vụ ví tạm thời không phản hồi; thử lại sau ít phút.",
    "PAYMENT_PROVIDER_UNCERTAIN": "Chưa xác định được kết quả từ cổng thanh toán; hệ thống cần đối soát.",
    "PAYMENT_TRANSACTION_INCOMPLETE": "Giao dịch trước với cùng mã chưa hoàn tất; hệ thống cần đối soát.",
    "PAYMENT_HOLD_INSUFFICIENT": "Khoản tiền giữ không đủ cho lần thanh toán này.",
    "PAYMENT_HOLD_NOT_FOUND": "Không tìm thấy khoản tiền giữ.",
    "PAYMENT_SELF_TRANSFER": "Không thể tự mua Note của chính mình.",
    "PAYMENT_LOAN_NOT_ACTIVE": "Khoản vay không còn hoạt động nên không thanh toán được.",
    "PAYMENT_LOAN_NOT_FOUND": "Không tìm thấy khoản vay để thanh toán.",
    "PAYMENT_LOAN_CHANGED": "Khoản vay đã thay đổi trong lúc lập báo giá; tải lại rồi thử lại.",
    "REPAYMENT_AMOUNT_NOT_SCHEDULED_DUE": (
        "Số tiền thanh toán phải đúng bằng nghĩa vụ đến hạn hiện tại do hệ thống lõi tính; tải lại số "
        "tiền rồi thanh toán lại."
    ),
    "REPAYMENT_DATE_INVALID": "Ngày trả nợ phải là ngày nghiệp vụ hiện tại.",
    "FINERACT_NO_SCHEDULED_DUE": "Khoản vay chưa có nghĩa vụ đến hạn để thanh toán.",
    "FINERACT_DUE_UNAVAILABLE": "Chưa lấy được số tiền đến hạn từ hệ thống lõi; thử lại sau ít phút.",
    "FINERACT_REPAYMENT_OUTCOME_UNKNOWN": (
        "Mất kết nối khi ghi khoản trả nợ; hệ thống phải đối soát trước khi cho thử lại."
    ),
    "EARLY_SETTLEMENT_QUOTE_NOT_ACTIVE": "Báo giá tất toán đã hết hiệu lực; lấy báo giá mới.",
    "EARLY_SETTLEMENT_QUOTE_NOT_FOUND": "Không tìm thấy báo giá tất toán.",
    "EARLY_SETTLEMENT_LOAN_CHANGED": "Khoản vay không còn khớp với báo giá tất toán; lấy báo giá mới.",
    "PARTIAL_PREPAYMENT_AMOUNT_TOO_HIGH": (
        "Phần gốc trả trước phải nhỏ hơn gốc còn lại; muốn trả hết thì dùng tất toán."
    ),
    "PARTIAL_PREPAYMENT_AMOUNT_INVALID": "Số tiền trả trước một phần không hợp lệ.",
    "PARTIAL_PREPAYMENT_QUOTE_NOT_ACTIVE": "Báo giá trả trước đã hết hiệu lực; lấy báo giá mới.",
    "PARTIAL_PREPAYMENT_QUOTE_NOT_FOUND": "Không tìm thấy báo giá trả trước.",
    "PARTIAL_PREPAYMENT_LOAN_CHANGED": "Khoản vay đã thay đổi so với báo giá trả trước; lấy báo giá mới.",
    "TOPUP_AMOUNT_OUT_OF_RANGE": "Số tiền nạp nằm ngoài giới hạn cho phép.",
    "TOPUP_PROVIDER_UNAVAILABLE": (
        "Chưa xác định được kết quả tạo giao dịch nạp tiền; kiểm tra lại lịch sử ví."
    ),
    "TOPUP_PROVIDER_UNCERTAIN": (
        "Chưa xác định được kết quả nạp tiền từ cổng thanh toán; hệ thống cần đối soát."
    ),
    # finora-loan: hồ sơ, điều khoản, hợp đồng
    "LOAN_APPLICATION_VERSION_CONFLICT": (
        "Hồ sơ vừa được cập nhật ở nơi khác; tải lại bản mới nhất rồi thao tác lại."
    ),
    "LOAN_CONTRACT_VERSION_CONFLICT": (
        "Hợp đồng vừa được cập nhật ở nơi khác; tải lại bản mới nhất rồi thao tác lại."
    ),
    "CONCURRENT_UPDATE_CONFLICT": "Dữ liệu vừa được cập nhật ở nơi khác; tải lại rồi thao tác lại.",
    "CREDIT_ASSESSMENT_NOT_LATEST": (
        "Kết quả chấm điểm đang xem không phải lần mới nhất của hồ sơ; tải lại hồ sơ rồi quyết định."
    ),
    "CREDIT_ASSESSMENT_RETRY_NOT_ALLOWED": "Hồ sơ không ở trạng thái cho phép chấm điểm lại.",
    "ASSESSMENT_NOT_ELIGIBLE_FOR_DECISION": "Kết quả chấm điểm này không dùng để ra quyết định được.",
    "ASSESSMENT_EXPLANATION_UNAVAILABLE": "Chưa có giải thích cho lần chấm điểm này.",
    "ADMIN_DECISION_REASON_INVALID": "Mã lý do không phù hợp với quyết định (duyệt hay từ chối).",
    "REJECTION_REASON_REQUIRED": "Từ chối hồ sơ cần chọn lý do.",
    "CORE_LENDING_UNAVAILABLE": (
        "Hệ thống tính lịch trả đang chuẩn bị hoặc tạm thời không sẵn sàng; thử lại sau khoảng 30 giây."
    ),
    "LOAN_TERMS_OUT_OF_RANGE": "Số tiền hoặc kỳ hạn nằm ngoài giới hạn của sản phẩm vay.",
    "LOAN_PRODUCT_NOT_AVAILABLE": "Sản phẩm vay hiện chưa nhận hồ sơ.",
    "LOAN_PRODUCT_CHANGED": "Sản phẩm vay vừa thay đổi; xem lại lịch trả dự kiến rồi nộp lại.",
    "PRICING_DISCLOSURE_OUTDATED": "Nội dung công bố lãi suất đã thay đổi; xem lại rồi xác nhận.",
    "PRICING_DISCLOSURE_NOT_ACCEPTED": "Cần xác nhận đã xem lãi suất và lịch trả dự kiến trước khi nộp.",
    "LOAN_PURPOSE_REQUIRED": "Cần chọn mục đích vay.",
    "LOAN_PURPOSE_DETAIL_REQUIRED": "Cần ghi rõ mục đích vay khi chọn mục đích Khác.",
    "PURPOSE_DETAIL_TOO_LONG": "Mô tả mục đích vay quá dài.",
    "HOME_OWNERSHIP_REQUIRED": "Cần chọn tình trạng nhà ở.",
    "DECLARED_MONTHLY_INCOME_INVALID": "Thu nhập hằng tháng khai báo không hợp lệ.",
    "MONTHLY_DEBT_OBLIGATIONS_INVALID": "Nghĩa vụ trả nợ hằng tháng khai báo không hợp lệ.",
    "EMPLOYMENT_LENGTH_INVALID": "Số năm làm việc khai báo không hợp lệ.",
    "TERMS_CONFIRMATION_EXPIRED": "Thời hạn xác nhận điều khoản đã hết.",
    "TERMS_CONFIRMATION_OUTDATED": "Điều khoản đã thay đổi; tải lại hồ sơ rồi xác nhận lại.",
    "TERMS_CONFIRMATION_NOT_PENDING": "Hồ sơ không còn chờ xác nhận điều khoản.",
    "LOAN_TERMS_NOT_AUTHORIZED_FOR_FUNDING": "Điều khoản cuối chưa được người vay cho phép đưa lên sàn.",
    "CONTRACT_EXPIRED": "Hợp đồng đã hết hạn ký.",
    "LENDER_SIGNATURE_WINDOW_CLOSED": "Hợp đồng không còn chờ chữ ký nhà đầu tư.",
    "CONTRACT_PARTY_ALREADY_SIGNED": "Bên này đã ký hợp đồng bằng một giao dịch khác.",
    "CONTRACT_PARTY_NOT_PENDING": "Bên này không còn ở trạng thái chờ ký.",
    "CONTRACT_PARTY_SIGNATURE_IN_PROGRESS": "Yêu cầu ký đang được xử lý; chờ kết quả rồi kiểm tra lại.",
    "INVESTOR_SIGNATURE_PENDING": "Chữ ký của nhà đầu tư chưa hoàn tất.",
    "LOAN_ACCESS_DENIED": "Không có quyền xem khoản vay này.",
    "LOAN_APPLICATION_ACCESS_DENIED": "Không có quyền xem hồ sơ này.",
    "LOAN_CONTRACT_ACCESS_DENIED": "Không có quyền xem hợp đồng này.",
    "CONTRACT_PARTY_ACCESS_DENIED": "Không có quyền ký hợp đồng này.",
    "LOAN_NOT_FOUND": "Không tìm thấy khoản vay.",
    "INVESTOR_ROLE_REQUIRED": "Chức năng này chỉ dành cho nhà đầu tư.",
    "ADMIN_ROLE_REQUIRED": "Chức năng này chỉ dành cho quản trị.",
    "WALLET_ROLE_REQUIRED": "Tài khoản chưa có vai trò được dùng ví.",
    # finora-loan: phục vụ khoản vay, cơ cấu, vận hành
    "LOAN_NOT_ACTIVE": "Chỉ khoản vay đang trả nợ hoặc nợ xấu mới được cơ cấu.",
    "ACTIVE_RESTRUCTURE_REQUEST_EXISTS": "Khoản vay đã có một yêu cầu cơ cấu chưa kết thúc.",
    "RESCHEDULE_DATE_IN_PAST": "Ngày bắt đầu cơ cấu không được ở quá khứ.",
    "INVALID_TERM_EXTENSION": "Gia hạn cần số kỳ thêm lớn hơn 0.",
    "INVALID_INSTALLMENT_ADJUSTMENT": "Yêu cầu dời ngày trả kỳ không hợp lệ.",
    "RESTRUCTURE_REQUEST_NOT_FOUND": "Không tìm thấy yêu cầu cơ cấu.",
    "RESTRUCTURE_TERMS_CHANGED": "Điều kiện cơ cấu đã thay đổi; tải lại rồi gửi lại yêu cầu.",
    "SERVICING_RECONCILIATION_REQUIRED": (
        "Số liệu từ hệ thống lõi vi phạm kiểm tra chéo; quản trị cần đối soát."
    ),
    "FINORA_LOAN_MAPPING_MISSING": "Chưa liên kết được sự kiện trả nợ với khoản vay FINORA.",
    "CORE_LOAN_ID_MISMATCH": "Lệch mã khoản vay giữa FINORA và hệ thống lõi.",
    "EXTERNAL_ID_MISMATCH": "Lệch mã tham chiếu giữa FINORA và hệ thống lõi.",
    "PRINCIPAL_DISBURSED_MISMATCH": "Lệch số tiền giải ngân giữa FINORA và hệ thống lõi.",
    "OUTSTANDING_BREAKDOWN_MISMATCH": "Lệch cơ cấu dư nợ giữa FINORA và hệ thống lõi.",
    "COLLECTION_CASE_NOT_FOUND": "Không tìm thấy hồ sơ thu hồi.",
    "COLLECTION_CASE_CLOSED": "Hồ sơ thu hồi đã đóng.",
    "COLLECTION_ACTION_INVALID": "Ghi nhận thu hồi không hợp lệ.",
    "CORE_PRODUCT_NOT_SYNCED": "Sản phẩm vay chưa đồng bộ sang hệ thống lõi.",
    "CORE_PRODUCT_SYNC_IN_PROGRESS": "Sản phẩm vay đang được đồng bộ sang hệ thống lõi.",
    "SMARTCA_UNAVAILABLE": "Không kết nối được dịch vụ ký số VNPT SmartCA; thử lại sau ít phút.",
}

# Mã có tiền tố động (Java ghép chuỗi), xét từ tiền tố dài tới ngắn.
_TIEN_TO: tuple[tuple[str, str], ...] = (
    (
        "FINERACT_FINAL_SCHEDULE_",
        "Chưa tính được lịch trả nợ cuối trên hệ thống lõi (Fineract); hệ thống sẽ thử lại hoặc "
        "chuyển chuyên viên thẩm định.",
    ),
    (
        "FINERACT_",
        "Hệ thống lõi tính lịch và ghi sổ khoản vay (Fineract) chưa phản hồi đúng; thường chỉ cần "
        "thử lại sau ít phút.",
    ),
    (
        "SMARTCA_",
        "Dịch vụ ký số VNPT SmartCA chưa xác nhận yêu cầu ký; kiểm tra ứng dụng SmartCA rồi thử lại.",
    ),
    ("KAFKA_", "Lỗi truyền sự kiện nội bộ giữa các dịch vụ; hệ thống tự gửi lại."),
    ("OUTBOX_", "Lỗi truyền sự kiện nội bộ giữa các dịch vụ; hệ thống tự gửi lại."),
)

# Căn cứ pháp lý của lý do chốt chặn, dạng `legal_refs` của kho kiến thức. Câu của
# DIEN_GIAI_CHOT_CHAN có nhắc "Bộ luật Dân sự", "Nghị định 94/2025", nên tool dùng câu đó
# phải gắn trích dẫn tương ứng; nếu không, guard (Task 13) sẽ coi là trích dẫn không có căn cứ.
_CAN_CU_LY_DO: dict[str, tuple[str, ...]] = {
    "INTEREST_RATE_EXCEEDS_LEGAL_LIMIT": ("LAW-CIVIL-2015:Điều 468",),
    "TERM_EXCEEDS_LEGAL_LIMIT": ("LAW-P2P-2025:Điều 11 khoản 1 điểm c",),
}

# LOAN-SERVICING-POLICY.md §4; ngưỡng khớp DebtGroup.fromDaysPastDue của finora-loan.
_TEN_NHOM_NO: dict[int, str] = {
    1: "Nhóm 1 — Đủ tiêu chuẩn",
    2: "Nhóm 2 — Cần chú ý",
    3: "Nhóm 3 — Dưới tiêu chuẩn (nợ xấu)",
    4: "Nhóm 4 — Nghi ngờ (nợ xấu)",
    5: "Nhóm 5 — Có khả năng mất vốn (nợ xấu)",
}
NHOM_NO_XAU_DAU_TIEN = 3

GHI_CHU_NHOM_NO = (
    "Nhóm nợ nội bộ của FINORA tính theo số ngày quá hạn, không phải kết quả tra cứu CIC."
)


def _chuan_hoa(ma: str | None) -> str | None:
    """Bỏ khoảng trắng, viết hoa; chuỗi rỗng coi như không có mã."""
    if ma is None:
        return None
    ma = str(ma).strip().upper()
    return ma or None


def _ma_la(ma: str) -> str:
    return f"{ma} {CHUA_CO_DIEN_GIAI}"


def _bang(nhom: str) -> dict[str, _TrangThai]:
    # Nhóm sai là lỗi lập trình ở tool, không phải dữ liệu lạ từ API: ném lỗi để test của
    # tool bắt được ngay, thay vì âm thầm trả "(chưa có diễn giải)".
    try:
        return _BANG_TRANG_THAI[nhom]
    except KeyError:
        raise ValueError(f"Nhóm trạng thái không hỗ trợ: {nhom!r}") from None


def dich_trang_thai(nhom: NhomTrangThai, ma: str | None) -> str:
    """Nhãn ngắn của một trạng thái, dùng cho thẻ số liệu và dữ kiện gửi LLM.

    `dich_trang_thai("application", "PENDING_REVIEW")` -> "Chờ thẩm định".
    Không có mã -> "—"; mã lạ -> "<MA> (chưa có diễn giải)".
    """
    bang = _bang(nhom)
    chuan = _chuan_hoa(ma)
    if chuan is None:
        return KHONG_CO
    muc = bang.get(chuan)
    return muc[0] if muc else _ma_la(chuan)


def giai_thich_trang_thai(nhom: NhomTrangThai, ma: str | None) -> str | None:
    """Câu giải thích trạng thái nghĩa là gì; `None` khi không có mã hoặc mã lạ.

    Trả `None` thay vì câu đoán để câu mẫu/LLM biết là không có giải thích và không bịa.
    """
    bang = _bang(nhom)
    chuan = _chuan_hoa(ma)
    if chuan is None:
        return None
    muc = bang.get(chuan)
    return muc[1] if muc else None


def _tra_ma(ma: str | None) -> str:
    chuan = _chuan_hoa(ma)
    if chuan is None:
        return KHONG_CO
    if chuan in _LY_DO:
        return _LY_DO[chuan]
    if chuan in _MA_LOI:
        return _MA_LOI[chuan]
    for tien_to, cau in _TIEN_TO:
        if chuan.startswith(tien_to):
            return cau
    return _ma_la(chuan)


def dich_ly_do(ma: str | None) -> str:
    """Dịch mã lý do (lịch sử hồ sơ, AI, admin, hủy lệnh) sang câu tiếng Việt.

    Dùng chung bảng tra với `dich_ma_loi`: lịch sử hồ sơ có thể chứa mã lỗi tích hợp
    (`AI_TIMEOUT`), còn `lastErrorCode` của app có thể là mã lý do — tra một nơi thì
    tool không phải đoán mã thuộc bảng nào. Hai bảng không trùng khóa (test kiểm).
    """
    return _tra_ma(ma)


def dich_ma_loi(ma: str | None) -> str:
    """Dịch mã lỗi API (PIN, eKYC, lệnh, Auto-Invest, ví, vận hành...) sang câu tiếng Việt."""
    return _tra_ma(ma)


def dich_danh_sach_ly_do(chuoi: str | None) -> list[str]:
    """Dịch chuỗi mã ngăn bởi dấu phẩy (như `rejectionReasons` Loan lưu) thành danh sách câu.

    Mã lặp lại chỉ dịch một lần, giữ thứ tự xuất hiện đầu tiên.
    """
    if not chuoi:
        return []
    ket_qua: list[str] = []
    da_gap: set[str] = set()
    for phan in chuoi.split(","):
        ma = _chuan_hoa(phan)
        if ma is None or ma in da_gap:
            continue
        da_gap.add(ma)
        ket_qua.append(_tra_ma(ma))
    return ket_qua


def can_cu_ly_do(ma: str | None) -> tuple[str, ...]:
    """Căn cứ pháp lý (`legal_refs`) của một mã lý do; rỗng khi lý do là chính sách FINORA."""
    chuan = _chuan_hoa(ma)
    if chuan is None:
        return ()
    return _CAN_CU_LY_DO.get(chuan, ())


def nhom_no_tu_dpd(dpd: int | None) -> int | None:
    """Nhóm nợ nội bộ 1–5 từ số ngày quá hạn (DPD).

    Ngưỡng ≤9→1, ≤90→2, ≤180→3, ≤360→4, còn lại 5 — đúng `DebtGroup.fromDaysPastDue` của
    finora-loan (nguồn duy nhất cho event, hồ sơ thu hồi và thống kê) và LOAN-SERVICING-POLICY
    §4. DPD âm hoặc 0 thuộc nhóm 1 như Java. Thiếu DPD trả `None`, không đoán.
    """
    if dpd is None:
        return None
    ngay = int(dpd)
    if ngay <= 9:
        return 1
    if ngay <= 90:
        return 2
    if ngay <= 180:
        return 3
    if ngay <= 360:
        return 4
    return 5


def ten_nhom_no(nhom: int | None) -> str:
    """Tên nhóm nợ cho thẻ số liệu, ví dụ 3 -> "Nhóm 3 — Dưới tiêu chuẩn (nợ xấu)"."""
    if nhom is None:
        return KHONG_CO
    return _TEN_NHOM_NO.get(int(nhom), f"Nhóm {nhom} {CHUA_CO_DIEN_GIAI}")


def la_no_xau(nhom: int | None) -> bool:
    """Nhóm 3 trở lên là nợ xấu nội bộ (DebtGroup.FIRST_NON_PERFORMING = 3)."""
    return nhom is not None and int(nhom) >= NHOM_NO_XAU_DAU_TIEN


# Kiểm khi import: Literal và bảng phải khớp, để thêm nhóm mà quên bảng (hoặc ngược lại)
# lộ ra ngay khi khởi động chứ không phải lúc người dùng hỏi.
assert set(get_args(NhomTrangThai)) == set(_BANG_TRANG_THAI), "NhomTrangThai lệch _BANG_TRANG_THAI"
