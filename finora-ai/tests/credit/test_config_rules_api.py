"""Test API cấu hình luật chấm điểm — /api/v1/ai/config/rules.

Trọng tâm là các ràng buộc CHẶN admin cấu hình sai. Ngưỡng hiện tại được chọn từ
150.000 hồ sơ thật và đạt AUC 0,6356; nếu API cho phép lưu cấu hình vô nghĩa thì
hệ thống vẫn chạy, vẫn trả về điểm, chỉ là điểm sai — kiểu lỗi tệ nhất với một
hệ thống tín dụng.
"""
import json

import pytest
from fastapi.testclient import TestClient

from app.services.credit import product_config
from main import app

URL = "/api/v1/ai/config/rules"


@pytest.fixture
def client(tmp_path, monkeypatch):
    """Client dùng file config tạm — test ghi thoải mái không hỏng config thật."""
    goc = product_config._CONFIG_PATH.read_text(encoding="utf-8")
    file_tam = tmp_path / "product_config.json"
    file_tam.write_text(goc, encoding="utf-8")
    monkeypatch.setattr(product_config, "_CONFIG_PATH", file_tam)
    product_config.reload()
    yield TestClient(app)
    product_config.reload()


def _body(client, sua=None) -> dict:
    """Dựng body PUT hợp lệ từ cấu hình hiện tại, cho phép chỉnh trước khi gửi."""
    rules = {}
    for luat in client.get(URL).json()["rules"]:
        muc = {"bat": luat["bat"], "diem_khi_thieu": luat["diem_khi_thieu"]}
        if luat["bac"] is not None:
            muc["bac"] = luat["bac"]
        if luat["bang_diem"] is not None:
            muc["bang_diem"] = luat["bang_diem"]
        rules[luat["ma"]] = muc
    if sua:
        sua(rules)
    return {"rules": rules}


class TestDocCauHinhLuat:
    def test_tra_ve_du_5_luat_kem_mo_ta(self, client):
        d = client.get(URL).json()
        assert len(d["rules"]) == 5
        for luat in d["rules"]:
            assert luat["mo_ta"] and luat["nhom_5c"] and luat["ma"]

    def test_co_du_thong_tin_de_frontend_dung_form(self, client):
        """Frontend cần biết luật nào tra bảng, luật nào so ngưỡng, chiều nào."""
        d = client.get(URL).json()
        nha_o = next(r for r in d["rules"] if r["ma"] == "CAPITAL_RESIDENCE_STABILITY")
        dti = next(r for r in d["rules"] if r["ma"] == "CAPACITY_EXISTING_DEBT")
        assert nha_o["la_bang_diem"] is True and nha_o["bang_diem"] is not None
        assert dti["la_bang_diem"] is False and dti["nghich_dao"] is True
        assert d["diem_toi_da_moi_luat"] == 20


class TestLuuCauHinhHopLe:
    def test_luu_lai_nguyen_ven(self, client):
        assert client.put(URL, json=_body(client)).status_code == 200

    def test_sua_nguong_roi_doc_lai_thay_gia_tri_moi(self, client):
        def sua(r):
            r["CHARACTER_CIC_HISTORY"]["bac"] = [[800, 20], [700, 15], [670, 10], [0, 5]]

        client.put(URL, json=_body(client, sua))
        moi = client.get(URL).json()["rules"]
        cic = next(r for r in moi if r["ma"] == "CHARACTER_CIC_HISTORY")
        assert cic["bac"][0] == [800, 20]

    def test_tat_luat_luu_duoc(self, client):
        client.put(URL, json=_body(client, lambda r: r["CHARACTER_CIC_HISTORY"].update(bat=False)))
        moi = client.get(URL).json()["rules"]
        assert next(r for r in moi if r["ma"] == "CHARACTER_CIC_HISTORY")["bat"] is False

    def test_ghi_xuong_file_json_dung_dinh_dang(self, client):
        client.put(URL, json=_body(client))
        luu = json.loads(product_config._CONFIG_PATH.read_text(encoding="utf-8"))
        assert set(luu["rules"]) == {r["ma"] for r in client.get(URL).json()["rules"]}
        # Không được làm mất các nhóm cấu hình khác.
        assert {"grades", "approval_thresholds", "model_weights", "legal_limits"} <= set(luu)


class TestChanCauHinhSai:
    def test_chan_diem_khong_giam_dan(self, client):
        """Bậc đầu phải là bậc tốt nhất, nếu không luật đảo ngược ý nghĩa."""
        def sua(r):
            r["CHARACTER_CIC_HISTORY"]["bac"] = [[740, 5], [700, 20], [670, 10], [0, 5]]

        assert client.put(URL, json=_body(client, sua)).status_code == 422

    def test_chan_nguong_sai_thu_tu(self, client):
        """DTI là chỉ số nghịch đảo — ngưỡng phải tăng dần."""
        def sua(r):
            r["CAPACITY_EXISTING_DEBT"]["bac"] = [[30, 20], [20, 15], [10, 8], [1e9, 2]]

        r = client.put(URL, json=_body(client, sua))
        assert r.status_code == 422
        assert "tăng dần" in r.json()["detail"]

    def test_chan_bac_tot_nhat_khong_dat_20(self, client):
        """Các luật phải cân nhau, nếu không trọng số giữa chúng bị lệch ngầm."""
        def sua(r):
            r["CHARACTER_CIC_HISTORY"]["bac"] = [[740, 15], [700, 10], [0, 5]]

        assert client.put(URL, json=_body(client, sua)).status_code == 422

    def test_chan_tat_het_luat(self, client):
        def sua(r):
            for muc in r.values():
                muc["bat"] = False

        r = client.put(URL, json=_body(client, sua))
        assert r.status_code == 422
        assert "ít nhất một luật" in r.json()["detail"]

    def test_chan_thieu_luat(self, client):
        r = client.put(URL, json=_body(client, lambda r: r.pop("CHARACTER_CIC_HISTORY")))
        assert r.status_code == 422

    def test_chan_ma_luat_la(self, client):
        def sua(r):
            r["LUAT_TU_CHE"] = {"bat": True, "diem_khi_thieu": 8, "bac": [[1, 20], [0, 5]]}

        assert client.put(URL, json=_body(client, sua)).status_code == 422

    def test_chan_bang_diem_thieu_khoa(self, client):
        def sua(r):
            r["CAPITAL_RESIDENCE_STABILITY"]["bang_diem"] = {"OWN": 20, "RENT": 8}

        assert client.put(URL, json=_body(client, sua)).status_code == 422

    def test_chan_dung_bac_cho_luat_tra_bang(self, client):
        """Nhà ở là giá trị rời rạc, không so ngưỡng được."""
        def sua(r):
            r["CAPITAL_RESIDENCE_STABILITY"].pop("bang_diem")
            r["CAPITAL_RESIDENCE_STABILITY"]["bac"] = [[1, 20], [0, 5]]

        assert client.put(URL, json=_body(client, sua)).status_code == 422

    @pytest.mark.parametrize("diem_thieu", [-1, 21])
    def test_chan_diem_khi_thieu_ngoai_thang(self, client, diem_thieu):
        def sua(r):
            r["CHARACTER_CIC_HISTORY"]["diem_khi_thieu"] = diem_thieu

        assert client.put(URL, json=_body(client, sua)).status_code == 422

    def test_cau_hinh_sai_khong_duoc_ghi_xuong_file(self, client):
        """Đây là điều quan trọng nhất: request lỗi không được để lại hậu quả."""
        truoc = product_config._CONFIG_PATH.read_text(encoding="utf-8")

        def sua(r):
            r["CHARACTER_CIC_HISTORY"]["bac"] = [[740, 5], [700, 20], [0, 5]]

        client.put(URL, json=_body(client, sua))
        assert product_config._CONFIG_PATH.read_text(encoding="utf-8") == truoc


class TestKhongPhaEndpointCu:
    def test_product_config_van_chay(self, client):
        """Thêm nhóm 'rules' vào file không được làm hỏng /config/product."""
        r = client.get("/api/v1/ai/config/product")
        assert r.status_code == 200
        assert {"grades", "approval_thresholds", "model_weights", "legal_limits"} <= set(r.json())
