"""Nhận ra yêu cầu "làm thay" một thao tác và trả lời từ chối bằng câu mẫu + nút mở đúng màn.

Trợ lý chỉ đọc (spec mục 1, common.md "Bot chỉ đọc"). Trước bản sửa fix-1, lời từ chối chỉ nằm trong prompt
LLM, nên khi không có LLM, hoặc khi câu khớp từ khóa một gợi ý dữ liệu ("Trả nợ giúp tôi kỳ này" khớp
"kỳ này"), người dùng nhận câu mẫu dữ liệu mà không ai nói trợ lý không làm thay (CHAT-005, chạy thật
16/10/2026). Giống `guard.detect_advice_request`, lời từ chối ở đây do code quyết định, không phụ thuộc model.

Một câu là yêu cầu làm thay khi có động từ thao tác của vai trò VÀ hoặc có dấu hiệu nhờ ("giúp", "hộ",
"thay tôi", "luôn đi", "ngay bây giờ"…), hoặc mở đầu bằng chính động từ đó như câu mệnh lệnh. Câu hỏi cách
làm/lý do ("trả nợ thế nào?", "vì sao không ký được?") và lời nhờ đọc ("giúp tôi xem…") không bị chặn. Mọi so
khớp trên bản bỏ dấu (`guard.chuan_cau`) để câu gõ không dấu cũng nhận ra.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field

from app.services.assistant.guard import chuan_cau
from app.services.assistant.principal import Toolset

CAU_TU_CHOI_THAO_TAC = (
    "Mình không thực hiện thao tác thay bạn được: Trợ lý FINORA chỉ đọc và giải thích thông tin."
)
CANH_BAO_PIN = "Đừng gửi mã PIN, OTP hay mật khẩu vào khung chat; trợ lý không bao giờ hỏi các mã này."


@dataclass(frozen=True)
class ThaoTac:
    """Một loại thao tác bot không làm thay, kèm hướng dẫn tự làm và nút điều hướng (bảng deeplinks)."""

    ma: str
    toolset: Toolset
    mau: re.Pattern[str]  # động từ thao tác, so trên chuan_cau (có khoảng trắng hai đầu)
    huong_dan: str
    hanh_dong: str | None = None  # khóa nút trong deeplinks.ACTIONS
    tham_so_co_dinh: Mapping[str, str] = field(default_factory=dict)  # ví dụ tab của trang vận hành
    tra_cuu: str | None = None  # công cụ liệt kê (chỉ đọc) để tìm mã cho nút khi màn đang mở không có
    hanh_dong_du_phong: str | None = None  # nút không cần mã, dùng khi không tìm được mã


def _mau(*cum: str) -> re.Pattern[str]:
    return re.compile(" (?:" + "|".join(cum) + ") ")


_W = r"(?:\w+ ){0,3}?"  # tối đa 3 chữ chen giữa động từ và tân ngữ ("đặt giúp tôi lệnh mua")
# Một số tiền đã bỏ dấu: "5 trieu", "500k", "1 5 ty" (dấu phẩy thành khoảng trắng), "200 nghin".
_SO_TIEN = r"\d+(?: \d+)?(?: ?(?:trieu|tr|k|nghin|ngan|ty|ti|dong|d))"

# Thứ tự là thứ tự ưu tiên: quy tắc cụ thể (tất toán, Auto-Invest, duyệt eKYC) đứng trước quy tắc chung.
QUY_TAC: tuple[ThaoTac, ...] = (
    # ---- Người vay ----
    ThaoTac("TAT_TOAN", Toolset.BORROWER, _mau("tat toan"),
            "Bạn tự tất toán trong app: mở khoản vay, chọn Tất toán trước hạn rồi xác nhận bằng mã PIN giao dịch.",
            hanh_dong="tat_toan", tra_cuu="khoan_vay_cua_toi"),
    ThaoTac("TRA_TRUOC", Toolset.BORROWER, _mau("tra truoc"),
            "Bạn tự trả trước trong app: mở khoản vay, chọn Trả trước một phần rồi xác nhận bằng mã PIN giao dịch.",
            hanh_dong="tra_truoc", tra_cuu="khoan_vay_cua_toi"),
    ThaoTac("GIA_HAN", Toolset.BORROWER, _mau("gia han", "co cau", "doi lich tra", "hoan no", "gian no"),
            "Bạn tự gửi yêu cầu cơ cấu trong app: mở khoản vay và chọn Cơ cấu khoản vay; FINORA xét duyệt yêu cầu.",
            hanh_dong="gia_han", tra_cuu="khoan_vay_cua_toi"),
    ThaoTac("TRA_NO", Toolset.BORROWER,
            _mau("tra no", "thanh toan", r"tra (?:tien )?ky", "dong tien", "tra tien vay"),
            "Bạn tự thanh toán trong app: mở khoản vay, chọn Thanh toán rồi xác nhận bằng mã PIN giao dịch; tiền "
            "được trừ từ số dư ví FINORA.",
            hanh_dong="tra_no", tra_cuu="khoan_vay_cua_toi"),
    ThaoTac("KY_HOP_DONG", Toolset.BORROWER,
            _mau(rf"ky {_W}hop dong", "ky hd", "ky giup", "ky ho", "ky thay", r"nhap (?:\w+ )?(?:ma )?(?:pin|otp)"),
            "Bạn tự ký trong app: mở hợp đồng, đọc bản PDF rồi xác nhận bằng mã PIN giao dịch trước hạn ký.",
            hanh_dong="mo_hop_dong", tra_cuu="hop_dong_cua_toi"),
    ThaoTac("HO_SO", Toolset.BORROWER,
            _mau(rf"rut {_W}ho so", rf"huy {_W}ho so", rf"sua {_W}ho so", "dong y dieu khoan", "tu choi dieu khoan",
                 "xac nhan dieu khoan"),
            "Bạn tự thao tác trong màn hồ sơ: rút hồ sơ khi trạng thái còn cho phép, hoặc đồng ý/từ chối điều khoản "
            "mới trước hạn.",
            hanh_dong="mo_ho_so", tra_cuu="ho_so_cua_toi"),
    ThaoTac("NOP_HO_SO", Toolset.BORROWER, _mau(rf"nop {_W}(?:ho so|don)", rf"tao {_W}ho so", "dang ky vay"),
            "Bạn tự nộp hồ sơ trong app: chọn một sản phẩm ở tab Sàn, tính thử lịch trả rồi bấm Nộp hồ sơ."),
    # ---- Nhà đầu tư ----
    ThaoTac("AUTO_INVEST", Toolset.INVESTOR,
            _mau(rf"(?:bat|tat|luu|doi|cai|chinh|sua|thiet lap|cap nhat) {_W}(?:auto invest|tu dong dau tu)",
                 r"(?:auto invest|tu dong dau tu) (?:\w+ ){0,2}?(?:bat|tat|luu)"),
            "Bạn tự cài trong màn Auto-Invest, bấm Lưu rồi xác nhận bằng mã PIN giao dịch.",
            hanh_dong="auto_invest"),
    ThaoTac("DAT_LENH", Toolset.INVESTOR,
            _mau(rf"dat {_W}lenh", rf"huy {_W}lenh", rf"sua {_W}lenh", "dat mua", "dat ban",
                 r"mua (?:\d+ )?notes?", r"ban (?:\d+ )?notes?"),
            "Bạn tự đặt, sửa hoặc hủy lệnh trong sổ lệnh của khoản vay rồi xác nhận bằng mã PIN giao dịch.",
            hanh_dong="so_lenh", hanh_dong_du_phong="lenh_cua_toi"),
    ThaoTac("GOP_VON", Toolset.INVESTOR,
            _mau("gop von", "rot von", r"dau tu (?:\w+ ){0,2}?(?:vao|cho) khoan", "mua khoan", "dat cam ket"),
            "Bạn tự góp vốn trong app: mở khoản vay trên Sàn, nhập số tiền rồi xác nhận bằng mã PIN giao dịch.",
            hanh_dong="mo_khoan_san"),
    ThaoTac("KY_HOP_DONG_DAU_TU", Toolset.INVESTOR,
            _mau(rf"ky {_W}hop dong", "ky hd", "ky giup", "ky ho", "ky thay", r"nhap (?:\w+ )?(?:ma )?(?:pin|otp)"),
            "Bạn tự ký trong mục Hợp đồng đầu tư cần ký và xác nhận bằng mã PIN giao dịch.",
            hanh_dong="ky_hop_dong_dau_tu"),
    # ---- Chung cho người vay và nhà đầu tư (khai báo theo từng vai trò) ----
    *(
        quy_tac
        for vai_tro in (Toolset.BORROWER, Toolset.INVESTOR)
        for quy_tac in (
            # Có số tiền thay cho chữ "tiền" ("nạp 5 triệu vào ví giúp tôi", "rút 2 triệu về tài khoản"): fix-2.
            ThaoTac("NAP_TIEN", vai_tro, _mau(rf"nap {_W}(?:tien|vao vi|vao tai khoan)", rf"nap {_SO_TIEN}"),
                    "Bạn tự nạp trong app: vào Ví, chọn Nạp tiền và làm theo hướng dẫn chuyển khoản.",
                    hanh_dong="nap_tien"),
            ThaoTac("RUT_TIEN", vai_tro,
                    _mau(rf"rut {_W}(?:tien|ve vi|ve tai khoan|ve ngan hang)", rf"rut {_SO_TIEN}", "rut von",
                         rf"chuyen {_W}tien", rf"chuyen {_SO_TIEN}"),
                    "Rút tiền chỉ do chính bạn thực hiện ở tab Ví và xác nhận bằng mã PIN giao dịch."),
            ThaoTac("TAI_KHOAN", vai_tro,
                    _mau(rf"(?:doi|cap nhat|sua|thay doi) {_W}(?:mat khau|so dien thoai|email|thong tin|tai khoan)"),
                    "Bạn tự cập nhật trong mục Thông tin tài khoản của app.",
                    hanh_dong="tai_khoan"),
            ThaoTac("DOI_PIN", vai_tro, _mau(rf"(?:doi|dat lai|tao|mo khoa) {_W}(?:ma )?pin"),
                    "Bạn tự đổi hoặc đặt lại mã PIN trong app: chọn “Quên mã PIN?” ở bước nhập PIN, hoặc vào tab "
                    "Hồ sơ."),
            ThaoTac("EKYC", vai_tro, _mau(rf"(?:lam|xac thuc|xac minh|chup) {_W}(?:ekyc|danh tinh|cccd)"),
                    "Bạn tự xác thực trong app: chụp CCCD và khuôn mặt theo hướng dẫn ở mục eKYC.",
                    hanh_dong="ekyc"),
        )
    ),
    # ---- Quản trị ----
    ThaoTac("DUYET_KYC", Toolset.ADMIN, _mau(rf"(?:duyet|tu choi|phe duyet) {_W}(?:ekyc|kyc)"),
            "Hồ sơ eKYC do chuyên viên duyệt trên trang Khách hàng eKYC.",
            hanh_dong="khach_hang_kyc"),
    ThaoTac("DUYET_CO_CAU", Toolset.ADMIN, _mau(rf"(?:duyet|tu choi|phe duyet) {_W}(?:co cau|gia han)"),
            "Yêu cầu cơ cấu do chuyên viên xử lý ở trang Vận hành khoản vay, tab cơ cấu.",
            hanh_dong="van_hanh", tham_so_co_dinh={"tab": "reschedule"}),
    ThaoTac("CHAM_DIEM_LAI", Toolset.ADMIN,
            _mau(r"cham (?:diem )?lai", r"chay lai (?:\w+ ){0,2}?(?:mo hinh|cham diem|ai)"),
            "Chấm lại chỉ làm được trên trang thẩm định, với kết quả chấm mới nhất của hồ sơ đang chờ thẩm định.",
            hanh_dong="mo_tham_dinh", hanh_dong_du_phong="hang_doi_tham_dinh"),
    ThaoTac("DUYET_HO_SO", Toolset.ADMIN,
            _mau(rf"(?:duyet|phe duyet|tu choi|bac) {_W}(?:ho so|hs|khoan vay|don)", "duyet luon", "duyet giup",
                 "duyet ho", "duyet di"),
            "Quyết định duyệt hay từ chối do chuyên viên thực hiện trên trang thẩm định của hồ sơ.",
            hanh_dong="mo_tham_dinh", hanh_dong_du_phong="hang_doi_tham_dinh"),
    ThaoTac("DOI_CHINH_SACH", Toolset.ADMIN,
            _mau(rf"(?:doi|sua|chinh|cap nhat|ha|tang|giam|thay doi|dat lai|them|xoa) {_W}"
                 r"(?:nguong|luat cham diem|bo luat|trong so|chinh sach|cau hinh)"),
            "Ngưỡng và bộ luật chấm điểm chỉ đổi ở trang Chính sách đánh giá AI bằng tài khoản quản trị; mỗi lần lưu "
            "tạo một phiên bản chính sách mới.",
            hanh_dong="chinh_sach_ai"),
    ThaoTac("GUI_NHAC_NO", Toolset.ADMIN,
            _mau(rf"(?:gui|nhan|ban) {_W}(?:email|thu|thong bao|tin nhan|sms)", "nhac no"),
            "Việc nhắc nợ và liên hệ khách làm ở trang Vận hành khoản vay, tab thu hồi nợ.",
            hanh_dong="van_hanh", tham_so_co_dinh={"tab": "collection"}),
    ThaoTac("SUA_DU_LIEU", Toolset.ADMIN,
            _mau(rf"(?:xoa|khoa|mo khoa|vo hieu) {_W}(?:ho so|tai khoan|du lieu|khach hang|nguoi dung|khoan vay)"),
            "Dữ liệu chỉ được sửa qua đúng màn nghiệp vụ của trang quản trị, có kiểm quyền và ghi nhật ký."),
)

# Dấu hiệu nhờ làm thay. "hộ" chỉ tính khi đi với người/tiểu từ: bỏ dấu thì "hồ sơ", "hỗ trợ", "hộ kinh doanh"
# cũng thành "ho".
_NHO_LAM = re.compile(
    r" (?:giup|gium|dum|ho (?:toi|minh|em|voi|nhe|nha|di|luon|ban)|thay (?:toi|minh|em|cho)|lam ho|nho ban"
    r"|ban hay|hay giup|lam on|vui long|luon di|ngay di|ngay bay gio|ngay lap tuc) "
)
# "giúp tôi xem/hiểu/kiểm tra…": nhờ đọc, không phải nhờ làm.
_NHO_DOC = re.compile(
    r" (?:giup|gium|dum) (?:toi |minh |em )?(?:xem|hieu|giai thich|kiem tra|tra cuu|tom tat|tinh|biet|tim|doc"
    r"|liet ke|so sanh|check) "
)
# Hỏi cách làm, lý do, thời gian: câu hỏi kiến thức, không phải yêu cầu.
_HOI_CACH = re.compile(
    r" (?:the nao|nhu nao|lam sao|lam the nao|ra sao|cach|vi sao|tai sao|bao lau|khi nao|bao gio|o dau|nhu the nao) "
)
# Lời mở đầu lịch sự trước động từ mệnh lệnh.
_MO_DAU = re.compile(r"^ (?:(?:ban|tro ly|finora|oi|hay|lam on|vui long|nho ban|giup toi|giup minh|cho toi) )+")


def _la_menh_lenh(cau: str, quy_tac: ThaoTac, text: str) -> bool:
    """Câu mở đầu bằng động từ thao tác ("Trả nợ kỳ này", "Duyệt hồ sơ LA-…") và không phải câu hỏi."""
    if text.rstrip().endswith("?"):
        return False
    than = " " + _MO_DAU.sub(" ", cau).lstrip()
    return quy_tac.mau.match(than) is not None


def detect_action_request(text: str, toolsets: Iterable[Toolset]) -> ThaoTac | None:
    """Quy tắc thao tác đầu tiên (theo thứ tự ưu tiên) mà câu đang nhờ làm thay; None nếu không phải."""
    cau = chuan_cau(text)
    if _HOI_CACH.search(cau):
        return None
    vai_tro = set(toolsets)
    # "… hộ", "… đi" ở cuối câu là lời nhờ/mệnh lệnh ("Ký hợp đồng hộ", "Đổi ngưỡng xuống 70 điểm đi").
    nho = (_NHO_LAM.search(cau) is not None or cau.endswith((" ho ", " di "))) and not _NHO_DOC.search(cau)
    for quy_tac in QUY_TAC:
        if quy_tac.toolset not in vai_tro or not quy_tac.mau.search(cau):
            continue
        if nho or _la_menh_lenh(cau, quy_tac, text):
            return quy_tac
    return None


def cau_tu_choi_thao_tac(quy_tac: ThaoTac, text: str) -> str:
    """Câu từ chối cố định + hướng dẫn tự làm; nhắc không gửi PIN khi câu nói tới PIN/OTP/mật khẩu."""
    phan = [CAU_TU_CHOI_THAO_TAC, quy_tac.huong_dan]
    if re.search(r" (?:pin|otp|mat khau) ", chuan_cau(text)):
        phan.append(CANH_BAO_PIN)
    return " ".join(phan)
