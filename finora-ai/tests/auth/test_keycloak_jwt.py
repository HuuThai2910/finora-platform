"""Test kiểm access token Keycloak: chữ ký, hạn, issuer, claim, vai trò, cách lấy token."""

import jwt
import pytest

from app.services.auth import keycloak_jwt
from app.services.auth.keycloak_jwt import AuthError, extract_token, get_verifier
from .jwt_factory import (
    ISSUER_LOCAL,
    FakeJwksClient,
    foreign_key,
    make_verifier,
    sign_token,
)

ISSUER_KHAC = "https://sso.khac.vn/realms/finora"


class TestTokenHopLe:
    def test_dung_principal_tu_claim(self):
        token = sign_token("ROLE_BORROWER", sub="kc-42", user_id="42")

        principal = make_verifier().verify(token)

        assert principal.user_id == "42"
        assert principal.subject == "kc-42"
        assert principal.roles == frozenset({"ROLE_BORROWER"})
        assert principal.token == token
        assert principal.expires_at == jwt.decode(token, options={"verify_signature": False})["exp"]

    def test_thieu_user_id_thi_dung_sub(self):
        """Token service account không có claim user_id — giống `SecurityUtils` của Java."""
        principal = make_verifier().verify(sign_token(sub="kc-sa", user_id=None))
        assert principal.user_id == "kc-sa"

    def test_nhieu_vai_tro_va_chuan_hoa_ten(self):
        """assignRole chỉ thêm vai trò; tên thiếu tiền tố hoặc viết thường được chuẩn hóa."""
        token = sign_token("ROLE_BORROWER", "investor", "offline_access")

        principal = make_verifier().verify(token)

        assert principal.has_role("ROLE_BORROWER")
        assert principal.has_role("ROLE_INVESTOR")
        assert principal.has_role("ROLE_OFFLINE_ACCESS")
        assert not principal.has_role("ROLE_ADMIN")

    def test_realm_access_sai_kieu_thi_khong_co_vai_tro(self):
        token = sign_token(extra={"realm_access": "ROLE_ADMIN"})
        assert make_verifier().verify(token).roles == frozenset()

    def test_chap_nhan_issuer_bat_ky_trong_danh_sach(self):
        verifier = make_verifier(issuers=(ISSUER_LOCAL, ISSUER_KHAC))
        assert verifier.verify(sign_token(iss=ISSUER_KHAC)).subject == "kc-sub-1"

    def test_khong_kiem_aud(self):
        """Service Java không kiểm aud; finora-ai phải chấp nhận cùng token đó."""
        token = sign_token(extra={"aud": "account"})
        assert make_verifier().verify(token).user_id == "27"

    def test_lech_dong_ho_duoi_60_giay_van_qua(self):
        assert make_verifier().verify(sign_token(exp_in=-30)).user_id == "27"


class TestTokenBiTuChoi:
    def _ma_loi(self, token: str, verifier=None) -> str:
        with pytest.raises(AuthError) as loi:
            (verifier or make_verifier()).verify(token)
        return loi.value.code

    def test_het_han(self):
        assert self._ma_loi(sign_token(exp_in=-120)) == "AUTH_EXPIRED"

    def test_sai_issuer(self):
        assert self._ma_loi(sign_token(iss=ISSUER_KHAC)) == "AUTH_INVALID"

    def test_issuer_chi_trung_mot_phan_van_bi_tu_choi(self):
        """So khớp chính xác: issuer là tiền tố/chuỗi con của issuer hợp lệ không được qua."""
        assert self._ma_loi(sign_token(iss="http://localhost:8180/realms/fin")) == "AUTH_INVALID"

    def test_sai_chu_ky(self):
        assert self._ma_loi(sign_token(key=foreign_key())) == "AUTH_INVALID"

    def test_kid_la(self):
        assert self._ma_loi(sign_token(kid="khoa-khac")) == "AUTH_INVALID"

    def test_chuoi_khong_phai_jwt(self):
        assert self._ma_loi("khong-phai-jwt") == "AUTH_INVALID"

    def test_thuat_toan_hs256_bi_chan(self):
        """Không cho ký đối xứng: chặn kiểu tấn công đổi alg sang HS256."""
        token = jwt.encode(
            {"iss": ISSUER_LOCAL, "sub": "x", "exp": 9999999999},
            "bi-mat-doan-duoc-dai-it-nhat-32-byte!!",
            algorithm="HS256",
            headers={"kid": "khoa-test"},
        )
        assert self._ma_loi(token) == "AUTH_INVALID"

    def test_thieu_sub(self):
        token = sign_token(extra={"sub": None})
        assert self._ma_loi(token) == "AUTH_INVALID"

    def test_jwks_khong_tai_duoc(self):
        verifier = make_verifier(
            jwks_client=FakeJwksClient(loi=jwt.PyJWKClientConnectionError("timeout"))
        )
        assert self._ma_loi(sign_token(), verifier) == "AUTH_UNAVAILABLE"


class TestLayToken:
    def test_bearer_uu_tien_hon_cookie(self):
        assert extract_token("Bearer tu-header", "tu-cookie") == "tu-header"

    def test_khong_co_header_thi_lay_cookie(self):
        assert extract_token(None, "tu-cookie") == "tu-cookie"

    def test_header_khong_phai_bearer_thi_lay_cookie(self):
        assert extract_token("Basic YWJjOmRlZg==", "tu-cookie") == "tu-cookie"

    def test_bearer_rong_thi_lay_cookie(self):
        assert extract_token("Bearer   ", "tu-cookie") == "tu-cookie"

    def test_khong_co_gi(self):
        assert extract_token(None, None) is None
        assert extract_token("", "  ") is None


class TestCauHinhTuEnv:
    @pytest.fixture(autouse=True)
    def _xoa_cache(self):
        get_verifier.cache_clear()
        yield
        get_verifier.cache_clear()

    def test_mac_dinh_la_keycloak_local(self, monkeypatch):
        monkeypatch.delenv("KEYCLOAK_JWKS_URL", raising=False)
        monkeypatch.delenv("KEYCLOAK_ISSUERS", raising=False)

        verifier = get_verifier()

        assert verifier.jwks_url == keycloak_jwt.MAC_DINH_JWKS_URL
        assert verifier.issuers == ("http://localhost:8180/realms/finora",)

    def test_doc_danh_sach_issuer_phan_tach_dau_phay(self, monkeypatch):
        monkeypatch.setenv("KEYCLOAK_JWKS_URL", "http://keycloak:8080/realms/finora/protocol/openid-connect/certs")
        monkeypatch.setenv("KEYCLOAK_ISSUERS", f" {ISSUER_LOCAL} , {ISSUER_KHAC} ,")

        verifier = get_verifier()

        assert verifier.jwks_url.startswith("http://keycloak:8080/")
        assert verifier.issuers == (ISSUER_LOCAL, ISSUER_KHAC)

    def test_danh_sach_issuer_rong_thi_bao_loi_ngay(self, monkeypatch):
        monkeypatch.setenv("KEYCLOAK_ISSUERS", " , ")
        with pytest.raises(ValueError):
            get_verifier()
