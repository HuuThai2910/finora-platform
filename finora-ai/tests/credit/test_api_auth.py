"""Quyền gọi các API chấm điểm: sửa cấu hình chỉ admin, /explain chỉ Loan hoặc admin.

Trước task AI-CHAT-001 các endpoint này mở qua gateway: ai cũng sửa được bộ luật chấm
điểm, và ai cũng gọi được /explain để tra CIC theo một số CCCD bất kỳ. Test đi qua JWT
thật (ký bằng khóa test) chứ không override dependency, để bắt cả lỗi gắn nhầm dependency.
"""

import pytest
from fastapi.testclient import TestClient

from app.api import credit_router
from app.services.credit import product_config
from main import app
from auth.jwt_factory import bearer, internal_headers, sign_token, use_test_verifier

PRODUCT_URL = "/api/v1/ai/config/product"
RULES_URL = "/api/v1/ai/config/rules"
EXPLAIN_URL = "/api/v1/ai/credit/explain"


@pytest.fixture
def client(tmp_path, monkeypatch):
    """Config tạm như test_config_rules_api, cộng verifier test cho JWT."""
    duong_dan_goc = product_config._CONFIG_PATH
    file_tam = tmp_path / "product_config.json"
    file_tam.write_text(duong_dan_goc.read_text(encoding="utf-8"), encoding="utf-8")
    monkeypatch.setattr(product_config, "_CONFIG_PATH", file_tam)
    product_config.reload()
    use_test_verifier(monkeypatch)
    monkeypatch.delenv("AI_INTERNAL_TOKEN", raising=False)
    yield TestClient(app)
    monkeypatch.setattr(product_config, "_CONFIG_PATH", duong_dan_goc)
    product_config.reload()


def _product_body(client) -> dict:
    hien_tai = client.get(PRODUCT_URL).json()
    return {
        "grades": hien_tai["grades"],
        "approval_thresholds": hien_tai["approval_thresholds"],
        "model_weights": hien_tai["model_weights"],
    }


def _rules_body(client) -> dict:
    return {
        "rules": [
            {k: v for k, v in luat.items() if v is not None}
            for luat in client.get(RULES_URL).json()["rules"]
        ]
    }


class TestDocCauHinhVanMo:
    """Loan và web đọc cấu hình để định giá/hiển thị; khóa GET sẽ làm gãy hai bên đó."""

    def test_get_product_khong_can_dang_nhap(self, client):
        assert client.get(PRODUCT_URL).status_code == 200

    def test_get_rules_khong_can_dang_nhap(self, client):
        assert client.get(RULES_URL).status_code == 200


class TestSuaCauHinhChiAdmin:
    @pytest.mark.parametrize("url, body_fn", [(PRODUCT_URL, _product_body), (RULES_URL, _rules_body)])
    def test_chua_dang_nhap_401(self, client, url, body_fn):
        r = client.put(url, json=body_fn(client))
        assert r.status_code == 401
        assert r.json()["code"] == "AUTH_REQUIRED"

    @pytest.mark.parametrize("url, body_fn", [(PRODUCT_URL, _product_body), (RULES_URL, _rules_body)])
    def test_khong_phai_admin_403(self, client, url, body_fn):
        r = client.put(url, json=body_fn(client), headers=bearer(sign_token("ROLE_INVESTOR")))
        assert r.status_code == 403
        assert r.json()["code"] == "ADMIN_ROLE_REQUIRED"

    @pytest.mark.parametrize("url, body_fn", [(PRODUCT_URL, _product_body), (RULES_URL, _rules_body)])
    def test_admin_qua_cookie_200(self, client, url, body_fn):
        """Web gửi cookie HttpOnly access_token, không gửi header Authorization."""
        body = body_fn(client)
        client.cookies.set("access_token", sign_token("ROLE_ADMIN"))
        r = client.put(url, json=body)
        assert r.status_code == 200, r.json()

    def test_ma_noi_bo_khong_mo_duoc_cau_hinh(self, client, monkeypatch):
        """Mã nội bộ của Loan chỉ dùng cho /explain, không thay được quyền admin."""
        r = client.put(PRODUCT_URL, json=_product_body(client), headers=internal_headers(monkeypatch))
        assert r.status_code == 401

    def test_bi_tu_choi_thi_khong_ghi_file(self, client):
        truoc = product_config._CONFIG_PATH.read_text(encoding="utf-8")
        client.put(RULES_URL, json=_rules_body(client), headers=bearer(sign_token("ROLE_BORROWER")))
        assert product_config._CONFIG_PATH.read_text(encoding="utf-8") == truoc


class TestExplainChiLoanHoacAdmin:
    """Body rỗng → 422 nghĩa là đã qua lớp quyền và tới bước kiểm dữ liệu."""

    @pytest.fixture(autouse=True)
    def _khong_nap_model(self, monkeypatch):
        # Không request nào trong nhóm này được phép tới bước chấm điểm thật.
        def _khong_duoc_goi():
            raise AssertionError("không được nạp model trong test quyền")

        credit_router.lay_bo_du_doan.cache_clear()
        monkeypatch.setattr(credit_router, "lay_bo_du_doan", _khong_duoc_goi)

    def test_khong_gi_ca_401(self, client):
        r = client.post(EXPLAIN_URL, json={})
        assert r.status_code == 401
        assert r.json()["code"] == "AUTH_REQUIRED"

    def test_ma_noi_bo_dung_qua_lop_quyen(self, client, monkeypatch):
        assert client.post(EXPLAIN_URL, json={}, headers=internal_headers(monkeypatch)).status_code == 422

    def test_ma_noi_bo_sai_401(self, client, monkeypatch):
        internal_headers(monkeypatch)
        r = client.post(EXPLAIN_URL, json={}, headers={"X-Internal-Token": "sai"})
        assert r.status_code == 401
        assert r.json()["code"] == "INTERNAL_TOKEN_INVALID"

    def test_admin_qua_lop_quyen(self, client):
        assert client.post(EXPLAIN_URL, json={}, headers=bearer(sign_token("ROLE_ADMIN"))).status_code == 422

    def test_nguoi_vay_403(self, client):
        """Người vay không được tự gọi /explain: SHAP thô là dữ liệu chỉ admin xem (LEGAL-DISCLOSURE-02)."""
        r = client.post(EXPLAIN_URL, json={}, headers=bearer(sign_token("ROLE_BORROWER")))
        assert r.status_code == 403
