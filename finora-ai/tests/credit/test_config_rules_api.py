"""Test API cấu hình luật chấm điểm — /api/v1/ai/config/rules.

Admin thêm/sửa/xoá luật tuỳ ý, nên trọng tâm là các ràng buộc CHẶN cấu hình sai.
Ngưỡng mặc định được chọn từ 150.000 hồ sơ thật và đạt AUC 0,6356; nếu API cho
phép lưu cấu hình vô nghĩa thì hệ thống vẫn chạy, vẫn trả về điểm, chỉ là điểm
sai — kiểu lỗi tệ nhất với một hệ thống tín dụng.
"""

import json

import pytest
from fastapi.testclient import TestClient

from app.services.credit import product_config
from main import app

URL = "/api/v1/ai/config/rules"

LUAT_TUOI = {
    "ma": "AGE_BRACKET",
    "mo_ta": "Tuổi người vay",
    "truong": "person_age",
    "nghich_dao": False,
    "trong_so": 1.0,
    "bat": True,
    "diem_khi_thieu": 10,
    "bac": [[25, 20], [0, 5]],
}

LUAT_MUC_DICH = {
    "ma": "PURPOSE_RISK",
    "mo_ta": "Mục đích vay",
    "truong": "purpose",
    "trong_so": 0.5,
    "bat": True,
    "diem_khi_thieu": 10,
    "bang_diem": {
        "debt_consolidation": 5,
        "credit_card": 8,
        "home_improvement": 20,
        "major_purchase": 15,
        "medical": 12,
        "car": 15,
        "small_business": 4,
        "moving": 10,
        "vacation": 6,
        "education": 18,
        "other": 8,
    },
}


@pytest.fixture
def client(tmp_path, monkeypatch):
    """Client dùng file config tạm — test ghi thoải mái không hỏng config thật."""
    duong_dan_goc = product_config._CONFIG_PATH
    file_tam = tmp_path / "product_config.json"
    file_tam.write_text(duong_dan_goc.read_text(encoding="utf-8"), encoding="utf-8")
    monkeypatch.setattr(product_config, "_CONFIG_PATH", file_tam)
    product_config.reload()
    yield TestClient(app)
    monkeypatch.setattr(product_config, "_CONFIG_PATH", duong_dan_goc)
    product_config.reload()


def _rules(client) -> list[dict]:
    """Danh sách luật hiện tại ở dạng gửi lại được cho PUT."""
    return [
        {k: v for k, v in luat.items() if v is not None}
        for luat in client.get(URL).json()["rules"]
    ]


def _body(client, sua=None) -> dict:
    """Dựng body PUT hợp lệ từ cấu hình hiện tại, cho phép chỉnh trước khi gửi."""
    rules = _rules(client)
    if sua:
        sua(rules)
    return {"rules": rules}


def _tim(rules: list[dict], ma: str) -> dict:
    return next(r for r in rules if r["ma"] == ma)


class TestDocCauHinhLuat:
    def test_tra_ve_5_luat_mac_dinh_kem_mo_ta(self, client):
        d = client.get(URL).json()
        assert len(d["rules"]) == 5
        for luat in d["rules"]:
            assert luat["mo_ta"] and luat["ma"] and luat["truong"]
            assert "nhom_5c" not in luat

    def test_co_danh_muc_truong_de_frontend_dung_form(self, client):
        """Frontend cần biết chọn được trường nào, kiểu gì, giá trị hợp lệ ra sao."""
        d = client.get(URL).json()
        truong = {t["ma"]: t for t in d["truong"]}
        assert "int_rate" not in truong
        assert truong["home_ownership"]["kieu"] == "phan_loai"
        assert set(truong["home_ownership"]["gia_tri_hop_le"]) == {
            "OWN",
            "MORTGAGE",
            "RENT",
            "OTHER",
        }
        assert truong["dti"]["kieu"] == "so" and truong["dti"]["nguon"] == "ho_so"
        assert truong["cic_score"]["nguon"] == "cic"
        assert d["diem_toi_da_moi_luat"] == 20

    def test_luat_mac_dinh_co_goi_y_va_trong_so(self, client):
        cic = _tim(client.get(URL).json()["rules"], "CHARACTER_CIC_HISTORY")
        assert cic["trong_so"] == 1.0
        assert "{moc}" in cic["goi_y"]


class TestLuuCauHinhHopLe:
    def test_luu_lai_nguyen_ven(self, client):
        assert client.put(URL, json=_body(client)).status_code == 200

    def test_them_luat_moi(self, client):
        r = client.put(URL, json=_body(client, lambda rs: rs.append(LUAT_TUOI)))
        assert r.status_code == 200, r.json()
        ma = [x["ma"] for x in client.get(URL).json()["rules"]]
        assert ma[-1] == "AGE_BRACKET" and len(ma) == 6

    def test_them_luat_phan_loai(self, client):
        r = client.put(URL, json=_body(client, lambda rs: rs.append(LUAT_MUC_DICH)))
        assert r.status_code == 200, r.json()
        moi = _tim(client.get(URL).json()["rules"], "PURPOSE_RISK")
        assert moi["bang_diem"]["education"] == 18 and moi["trong_so"] == 0.5

    def test_xoa_luat_mac_dinh(self, client):
        r = client.put(URL, json=_body(client, lambda rs: rs.pop(0)))
        assert r.status_code == 200
        assert "CHARACTER_CIC_HISTORY" not in {
            x["ma"] for x in client.get(URL).json()["rules"]
        }

    def test_doi_thu_tu_luat(self, client):
        r = client.put(URL, json=_body(client, lambda rs: rs.reverse()))
        assert r.status_code == 200
        assert client.get(URL).json()["rules"][0]["ma"] == "CAPITAL_RESIDENCE_STABILITY"

    def test_sua_nguong_roi_doc_lai_thay_gia_tri_moi(self, client):
        def sua(rs):
            _tim(rs, "CHARACTER_CIC_HISTORY")["bac"] = [
                [800, 20],
                [700, 15],
                [670, 10],
                [0, 5],
            ]

        client.put(URL, json=_body(client, sua))
        cic = _tim(client.get(URL).json()["rules"], "CHARACTER_CIC_HISTORY")
        assert cic["bac"][0] == [800, 20]

    def test_sua_trong_so(self, client):
        client.put(
            URL,
            json=_body(
                client,
                lambda rs: _tim(rs, "CHARACTER_CIC_HISTORY").update(trong_so=2.5),
            ),
        )
        assert (
            _tim(client.get(URL).json()["rules"], "CHARACTER_CIC_HISTORY")["trong_so"]
            == 2.5
        )

    def test_tat_luat_luu_duoc(self, client):
        client.put(
            URL,
            json=_body(
                client, lambda rs: _tim(rs, "CHARACTER_CIC_HISTORY").update(bat=False)
            ),
        )
        assert (
            _tim(client.get(URL).json()["rules"], "CHARACTER_CIC_HISTORY")["bat"]
            is False
        )

    def test_goi_y_trong_duoc_luu_la_none(self, client):
        client.put(
            URL,
            json=_body(
                client, lambda rs: _tim(rs, "CHARACTER_CIC_HISTORY").update(goi_y="")
            ),
        )
        assert (
            _tim(client.get(URL).json()["rules"], "CHARACTER_CIC_HISTORY")["goi_y"]
            is None
        )

    def test_luat_moi_anh_huong_ngay_toi_cham_diem(self, client):
        """Cấu hình có hiệu lực ngay, không cần khởi động lại service."""
        from app.services.credit.rule_engine import cham_diem_chi_tiet

        client.put(URL, json=_body(client, lambda rs: rs.append(LUAT_TUOI)))
        _, vet = cham_diem_chi_tiet({"person_age": 30})
        assert any(m["ma"] == "AGE_BRACKET" and m["diem"] == 20 for m in vet)

    def test_ghi_xuong_file_json_dung_dinh_dang(self, client):
        client.put(URL, json=_body(client, lambda rs: rs.append(LUAT_TUOI)))
        luu = json.loads(product_config._CONFIG_PATH.read_text(encoding="utf-8"))
        assert isinstance(luu["rules"], list)
        assert [r["ma"] for r in luu["rules"]] == [
            r["ma"] for r in client.get(URL).json()["rules"]
        ]
        # Không được làm mất các nhóm cấu hình khác.
        assert {
            "grades",
            "approval_thresholds",
            "model_weights",
            "legal_limits",
        } <= set(luu)


class TestChanCauHinhSai:
    def _chan(self, client, sua, chua: str | None = None):
        r = client.put(URL, json=_body(client, sua))
        assert r.status_code == 422, r.json()
        if chua:
            assert chua in json.dumps(r.json(), ensure_ascii=False), r.json()
        return r

    def test_chan_danh_sach_rong(self, client):
        self._chan(client, lambda rs: rs.clear())

    def test_chan_diem_khong_giam_dan(self, client):
        """Bậc đầu phải là bậc tốt nhất, nếu không luật đảo ngược ý nghĩa."""
        self._chan(
            client,
            lambda rs: _tim(rs, "CHARACTER_CIC_HISTORY").update(
                bac=[[740, 5], [700, 20], [670, 10], [0, 5]]
            ),
        )

    def test_chan_nguong_sai_thu_tu(self, client):
        """DTI là chỉ số nghịch đảo — ngưỡng phải tăng dần."""
        self._chan(
            client,
            lambda rs: _tim(rs, "CAPACITY_EXISTING_DEBT").update(
                bac=[[30, 20], [20, 15], [10, 8], [1e9, 2]]
            ),
            "tăng dần",
        )

    def test_chan_bac_tot_nhat_khong_dat_20(self, client):
        """Các luật phải cân nhau qua trọng số tường minh, không qua điểm bậc."""
        self._chan(
            client,
            lambda rs: _tim(rs, "CHARACTER_CIC_HISTORY").update(
                bac=[[740, 15], [700, 10], [0, 5]]
            ),
        )

    def test_chan_tat_het_luat(self, client):
        self._chan(
            client, lambda rs: [r.update(bat=False) for r in rs], "ít nhất một luật"
        )

    def test_chan_ma_luat_trung(self, client):
        self._chan(
            client,
            lambda rs: rs.append({**LUAT_TUOI, "ma": "CHARACTER_CIC_HISTORY"}),
            "trùng",
        )

    @pytest.mark.parametrize("ma", ["luat thuong", "1ABC", "A", "a_b", "AB-C"])
    def test_chan_ma_luat_sai_dinh_dang(self, client, ma):
        self._chan(client, lambda rs: rs.append({**LUAT_TUOI, "ma": ma}))

    def test_chan_truong_khong_co_trong_danh_muc(self, client):
        self._chan(
            client,
            lambda rs: rs.append({**LUAT_TUOI, "truong": "so_dien_thoai"}),
            "danh mục",
        )

    def test_chan_int_rate_la_leakage(self, client):
        """Lãi suất không nằm trong danh mục — API phải chặn dù admin cố tình gửi."""
        self._chan(client, lambda rs: rs.append({**LUAT_TUOI, "truong": "int_rate"}))

    def test_chan_mo_ta_rong(self, client):
        self._chan(client, lambda rs: rs.append({**LUAT_TUOI, "mo_ta": ""}))

    @pytest.mark.parametrize("trong_so", [0, -1, 11])
    def test_chan_trong_so_ngoai_khoang(self, client, trong_so):
        self._chan(client, lambda rs: rs.append({**LUAT_TUOI, "trong_so": trong_so}))

    def test_chan_bang_diem_thieu_khoa(self, client):
        self._chan(
            client,
            lambda rs: _tim(rs, "CAPITAL_RESIDENCE_STABILITY").update(
                bang_diem={"OWN": 20, "RENT": 8}
            ),
        )

    def test_chan_bang_diem_thua_khoa(self, client):
        def sua(rs):
            _tim(rs, "CAPITAL_RESIDENCE_STABILITY")["bang_diem"]["CASTLE"] = 20

        self._chan(client, sua)

    def test_chan_dung_bac_cho_truong_phan_loai(self, client):
        """Nhà ở là giá trị rời rạc, không so ngưỡng được."""

        def sua(rs):
            r = _tim(rs, "CAPITAL_RESIDENCE_STABILITY")
            r.pop("bang_diem")
            r["bac"] = [[1, 20], [0, 5]]

        self._chan(client, sua, "bang_diem")

    def test_chan_dung_bang_diem_cho_truong_so(self, client):
        def sua(rs):
            r = _tim(rs, "CHARACTER_CIC_HISTORY")
            r.pop("bac")
            r["bang_diem"] = {"OWN": 20}

        self._chan(client, sua, "bac")

    def test_chan_it_hon_2_bac(self, client):
        self._chan(client, lambda rs: rs.append({**LUAT_TUOI, "bac": [[0, 20]]}))

    @pytest.mark.parametrize("diem_thieu", [-1, 21])
    def test_chan_diem_khi_thieu_ngoai_thang(self, client, diem_thieu):
        self._chan(
            client,
            lambda rs: _tim(rs, "CHARACTER_CIC_HISTORY").update(
                diem_khi_thieu=diem_thieu
            ),
        )

    def test_chan_goi_y_co_cho_trong_la(self, client):
        """Chỉ `{moc}` được thay thế; chỗ trống khác sẽ nổ lúc format hoặc lộ ra người vay."""
        self._chan(
            client,
            lambda rs: rs.append({**LUAT_TUOI, "goi_y": "Đạt {nguong} tuổi"}),
            "{moc}",
        )

    def test_chan_goi_y_qua_dai(self, client):
        self._chan(client, lambda rs: rs.append({**LUAT_TUOI, "goi_y": "x" * 301}))

    def test_cau_hinh_sai_khong_duoc_ghi_xuong_file(self, client):
        """Đây là điều quan trọng nhất: request lỗi không được để lại hậu quả."""
        truoc = product_config._CONFIG_PATH.read_text(encoding="utf-8")
        client.put(
            URL,
            json=_body(
                client, lambda rs: rs.append({**LUAT_TUOI, "truong": "int_rate"})
            ),
        )
        assert product_config._CONFIG_PATH.read_text(encoding="utf-8") == truoc


class TestKhongPhaEndpointCu:
    def test_product_config_van_chay(self, client):
        r = client.get("/api/v1/ai/config/product")
        assert r.status_code == 200
        assert {
            "grades",
            "approval_thresholds",
            "model_weights",
            "legal_limits",
        } <= set(r.json())
