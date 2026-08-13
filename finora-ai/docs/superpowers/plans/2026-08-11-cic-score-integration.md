
# Tích hợp CIC Score vào mô hình AI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thêm `cic_score` (điểm tín dụng CIC 150–750) làm feature cho mô hình XGBoost trong finora-ai, bằng cách tự gọi cic-service qua HTTP.

**Architecture:** finora-ai nhận `so_cccd` trong request, gọi `GET cic-service:8082/api/v1/diem-tin-dung/{soCccd}` để lấy `diemCic`. Điểm này trở thành feature `cic_score` trong pipeline ML (cùng missing indicator `cic_score_missing`). Khi CIC không khả dụng, dùng median + missing indicator — không chặn luồng scoring.

**Tech Stack:** Python 3.11+, FastAPI, httpx (async HTTP client), XGBoost, pandas, numpy, pytest

## Global Constraints

- Tất cả đơn vị tiền tệ: VNĐ
- CIC score thang 150–750 (từ cic-service port 8082)
- Rule engine (4×25 điểm) KHÔNG ĐỔI
- Model cần retrain → phiên bản mới (nằm ngoài scope plan này, nhưng code infrastructure sẵn sàng)
- CIC timeout: 3 giây, không retry, lỗi → tiếp tục với `cic_score = None`
- Giữ backward compatible: `so_cccd` là optional, request cũ (không có CCCD) vẫn hoạt động

## File Structure

| File | Trách nhiệm | Thay đổi |
|---|---|---|
| `requirements.txt` | Dependencies | +`httpx` |
| `app/services/cic_client.py` | HTTP client gọi cic-service | **Tạo mới** |
| `app/schemas/credit.py` | Pydantic request/response | +`so_cccd` (req), +`cic_score` (res) |
| `app/ml/features.py` | Danh sách features cho model | +`cic_score` numeric, +`cic_score_missing` |
| `app/ml/predictor.py` | Pipeline dự đoán | Nhận CIC score, truyền vào feature dict |
| `app/api/credit_router.py` | FastAPI endpoint | Gọi CIC client, truyền kết quả xuống |
| `tests/test_cic_client.py` | Test CIC client | **Tạo mới** |
| `tests/test_cic_integration.py` | Test tích hợp pipeline | **Tạo mới** |

---

### Task 1: CIC HTTP Client

**Files:**
- Create: `app/services/cic_client.py`
- Modify: `requirements.txt`
- Test: `tests/test_cic_client.py`

**Interfaces:**
- Consumes: cic-service `GET /api/v1/diem-tin-dung/{soCccd}` → `{"soCccd": str, "diemCic": int, ...}`
- Produces: `async tra_diem_cic(so_cccd: str) -> int | None` — trả `diemCic` (150–750) hoặc `None` khi lỗi

- [ ] **Step 1: Thêm httpx vào requirements.txt**

Mở `requirements.txt`, thêm dòng cuối:

```
httpx==0.28.1
```

- [ ] **Step 2: Viết test cho CIC client**

Tạo `tests/test_cic_client.py`:

```python
"""Test CIC client — mock HTTP, không cần cic-service thật."""
import pytest
import httpx

from app.services.cic_client import CicClient


@pytest.fixture
def client():
    return CicClient(base_url="http://localhost:8082")


class TestTraDiemCic:
    """tra_diem_cic() gọi GET /api/v1/diem-tin-dung/{soCccd}."""

    @pytest.mark.asyncio
    async def test_tra_ve_diem_khi_thanh_cong(self, client, httpx_mock):
        """CIC trả 200 với diemCic hợp lệ → trả về int."""
        httpx_mock.add_response(
            url="http://localhost:8082/api/v1/diem-tin-dung/012345678901",
            json={"soCccd": "012345678901", "diemCic": 580, "thoiDiemTraCuu": "2026-08-11T10:00:00+07:00"},
        )
        ket_qua = await client.tra_diem_cic("012345678901")
        assert ket_qua == 580

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_timeout(self, client, httpx_mock):
        """CIC timeout → trả None, không raise."""
        httpx_mock.add_exception(
            httpx.ReadTimeout("timeout"),
            url="http://localhost:8082/api/v1/diem-tin-dung/012345678901",
        )
        ket_qua = await client.tra_diem_cic("012345678901")
        assert ket_qua is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_500(self, client, httpx_mock):
        """CIC trả 500 → trả None, không raise."""
        httpx_mock.add_response(
            url="http://localhost:8082/api/v1/diem-tin-dung/012345678901",
            status_code=500,
        )
        ket_qua = await client.tra_diem_cic("012345678901")
        assert ket_qua is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_connection_error(self, client, httpx_mock):
        """CIC không khả dụng → trả None, không raise."""
        httpx_mock.add_exception(
            httpx.ConnectError("connection refused"),
            url="http://localhost:8082/api/v1/diem-tin-dung/012345678901",
        )
        ket_qua = await client.tra_diem_cic("012345678901")
        assert ket_qua is None
```

- [ ] **Step 3: Chạy test — phải FAIL vì chưa có module**

```bash
cd finora-ai
pip install httpx pytest-httpx pytest-asyncio
pytest tests/test_cic_client.py -v
```

Expected: `ModuleNotFoundError: No module named 'app.services.cic_client'`

- [ ] **Step 4: Implement CIC client**

Tạo `app/services/cic_client.py`:

```python
"""
HTTP client gọi cic-service để lấy điểm tín dụng CIC.

Thiết kế fail-open: khi cic-service không khả dụng (timeout, lỗi mạng, 5xx),
trả None thay vì raise — pipeline scoring tiếp tục với missing indicator.
Không retry: mỗi lần gọi CIC thêm latency vào luồng scoring vốn đã async,
một lần timeout 3 giây là đủ chấp nhận.
"""
import logging
import os

import httpx

logger = logging.getLogger(__name__)

CIC_BASE_URL = os.getenv("CIC_SERVICE_URL", "http://localhost:8082")
CIC_TIMEOUT_SECONDS = float(os.getenv("CIC_TIMEOUT_SECONDS", "3.0"))


class CicClient:
    """Client gọi cic-service lấy điểm tín dụng theo số CCCD."""

    def __init__(
        self,
        base_url: str = CIC_BASE_URL,
        timeout: float = CIC_TIMEOUT_SECONDS,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    async def tra_diem_cic(self, so_cccd: str) -> int | None:
        """Tra điểm CIC theo số CCCD.

        Returns:
            diemCic (int, 150–750) nếu thành công, None nếu lỗi.
        """
        url = f"{self.base_url}/api/v1/diem-tin-dung/{so_cccd}"
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as http:
                response = await http.get(url)

            if response.status_code != 200:
                logger.warning(
                    "CIC trả mã %d cho CCCD %s...%s",
                    response.status_code, so_cccd[:3], so_cccd[-3:],
                )
                return None

            return response.json()["diemCic"]

        except (httpx.HTTPError, KeyError, Exception) as loi:
            logger.warning(
                "Không lấy được điểm CIC cho CCCD %s...%s: %s",
                so_cccd[:3], so_cccd[-3:], loi,
            )
            return None
```

- [ ] **Step 5: Chạy test — phải PASS**

```bash
pytest tests/test_cic_client.py -v
```

Expected: 4 passed

- [ ] **Step 6: Commit**

```bash
git add requirements.txt app/services/cic_client.py tests/test_cic_client.py
git commit -m "feat(ai): add CIC HTTP client with fail-open design"
```

---

### Task 2: Schema — thêm `so_cccd` và `cic_score`

**Files:**
- Modify: `app/schemas/credit.py`
- Test: `tests/test_schemas.py`

**Interfaces:**
- Consumes: không
- Produces: `CreditScoreRequest.so_cccd: str | None`, `CreditScoreResponse.cic_score: int | None`

- [ ] **Step 1: Viết test cho schema mới**

Tạo `tests/test_schemas.py`:

```python
"""Test schema thay đổi: so_cccd trong request, cic_score trong response."""
from app.schemas.credit import CreditScoreRequest, CreditScoreResponse


class TestCreditScoreRequestSoCccd:
    """so_cccd là optional, backward compatible."""

    def test_request_khong_co_cccd_van_hop_le(self):
        """Request cũ (không có so_cccd) vẫn validate thành công."""
        req = CreditScoreRequest(
            annual_inc=300_000_000,
            loan_amnt=50_000_000,
            purpose="debt_consolidation",
            home_ownership="MORTGAGE",
        )
        assert req.so_cccd is None

    def test_request_co_cccd_12_ky_tu(self):
        """CCCD 12 ký tự → lưu đúng."""
        req = CreditScoreRequest(
            annual_inc=300_000_000,
            loan_amnt=50_000_000,
            purpose="debt_consolidation",
            home_ownership="MORTGAGE",
            so_cccd="012345678901",
        )
        assert req.so_cccd == "012345678901"


class TestCreditScoreResponseCicScore:
    """cic_score là optional trong response."""

    def test_response_co_cic_score(self):
        res = CreditScoreResponse(
            pd_probability=0.12,
            risk_score=65,
            evaluation_score=72.5,
            credit_grade="B",
            suggested_limit=80_000_000,
            decision="APPROVED",
            rejection_reason=None,
            model_version="11.0.0",
            cic_score=580,
        )
        assert res.cic_score == 580

    def test_response_khong_co_cic_score(self):
        res = CreditScoreResponse(
            pd_probability=0.12,
            risk_score=65,
            evaluation_score=72.5,
            credit_grade="B",
            suggested_limit=80_000_000,
            decision="APPROVED",
            rejection_reason=None,
            model_version="10.0.0",
        )
        assert res.cic_score is None
```

- [ ] **Step 2: Chạy test — phải FAIL**

```bash
pytest tests/test_schemas.py -v
```

Expected: FAIL — `so_cccd` và `cic_score` chưa tồn tại

- [ ] **Step 3: Sửa `app/schemas/credit.py` — thêm `so_cccd` vào request**

Trong `CreditScoreRequest`, thêm field `so_cccd` sau block tùy chọn (trước `model_config`):

```python
    # ── Tra cứu CIC ──────────────────────────────────────────────────────────
    so_cccd: str | None = Field(
        default=None,
        min_length=12,
        max_length=12,
        pattern=r"^\d{12}$",
        description="Số CCCD 12 chữ số. Có thì tra điểm CIC, không có thì bỏ qua.",
    )
```

Trong `model_config["json_schema_extra"]["example"]`, thêm:

```python
                "so_cccd": "012345678901",
```

- [ ] **Step 4: Sửa `app/schemas/credit.py` — thêm `cic_score` vào response**

Trong `CreditScoreResponse`, thêm field sau `model_version`:

```python
    cic_score: int | None = Field(
        default=None,
        description="Điểm tín dụng CIC (150-750). None nếu không tra được hoặc không có CCCD.",
    )
```

- [ ] **Step 5: Chạy test — phải PASS**

```bash
pytest tests/test_schemas.py -v
```

Expected: 4 passed

- [ ] **Step 6: Commit**

```bash
git add app/schemas/credit.py tests/test_schemas.py
git commit -m "feat(ai): add so_cccd to request and cic_score to response schema"
```

---

### Task 3: Feature pipeline — thêm `cic_score` vào FEATURE_NAMES

**Files:**
- Modify: `app/ml/features.py`
- Test: `tests/test_features.py`

**Interfaces:**
- Consumes: không
- Produces: `FEATURE_NAMES` chứa `"cic_score"`, `COLUMNS_WITH_MISSING` chứa `"cic_score"`, `MISSING_INDICATORS` chứa `"cic_score_missing"`

- [ ] **Step 1: Viết test cho feature list mới**

Tạo `tests/test_features.py`:

```python
"""Test feature list sau khi thêm cic_score."""
from app.ml.features import (
    FEATURE_NAMES,
    NUMERIC_FEATURES,
    COLUMNS_WITH_MISSING,
    MISSING_INDICATORS,
)


class TestCicScoreInFeatures:
    def test_cic_score_trong_numeric_features(self):
        assert "cic_score" in NUMERIC_FEATURES

    def test_cic_score_trong_columns_with_missing(self):
        assert "cic_score" in COLUMNS_WITH_MISSING

    def test_cic_score_missing_trong_missing_indicators(self):
        assert "cic_score_missing" in MISSING_INDICATORS

    def test_cic_score_trong_feature_names(self):
        assert "cic_score" in FEATURE_NAMES
        assert "cic_score_missing" in FEATURE_NAMES

    def test_khong_co_cic_score_trung_lap(self):
        """Không có feature nào bị trùng."""
        assert len(FEATURE_NAMES) == len(set(FEATURE_NAMES))
```

- [ ] **Step 2: Chạy test — phải FAIL**

```bash
pytest tests/test_features.py -v
```

Expected: FAIL — `cic_score` chưa có trong lists

- [ ] **Step 3: Sửa `app/ml/features.py`**

Thêm `"cic_score"` vào cuối `COLUMNS_WITH_MISSING`:

```python
COLUMNS_WITH_MISSING = [
    "person_age",
    "emp_length_years",
    "dti",
    "delinq_2yrs",
    "pub_rec",
    "int_rate",
    "installment",
    "cic_score",
]
```

Thêm `"cic_score"` vào cuối `NUMERIC_FEATURES` (trước 3 cột dẫn xuất):

```python
NUMERIC_FEATURES = [
    # Character — nhân thân
    "person_age",             # CCCD qua eKYC
    "emp_length_years",       # Hợp đồng lao động / tự khai
    # Capacity — khả năng trả nợ
    "annual_inc",             # Tự khai + sao kê lương
    # Conditions — điều kiện khoản vay
    "loan_amnt",              # Form nộp hồ sơ
    # Các đặc trưng tài chính bổ sung
    "dti",                    # Tỷ lệ nợ/thu nhập
    "term_months",            # Kỳ hạn vay (tháng)
    "delinq_2yrs",            # Số lần trễ hạn trong 2 năm
    "pub_rec",                # Hồ sơ công khai xấu
    "int_rate",               # Lãi suất danh nghĩa của gói vay (%)
    "installment",            # Số tiền phải trả hàng tháng
    # CIC — lịch sử tín dụng
    "cic_score",              # Điểm tín dụng CIC (150-750) từ cic-service
    # Đặc trưng dẫn xuất
    "log_income",             # log(annual_inc) — nén đuôi phân phối lệch phải
    "loan_to_income",         # loan_amnt / annual_inc
    "effective_apr",          # Chi phí thật — so sánh được giữa 3 phương pháp tính lãi
]
```

Cập nhật docstring module (dòng đầu) — bỏ phần nói "KHÔNG dùng CIC", thay bằng:

```python
"""
Bộ đặc trưng cho mô hình chấm điểm tín dụng.

Nguồn dữ liệu:
  - Hồ sơ người vay tự khai trên app (thu nhập, thâm niên việc làm, nhà ở, mục đích vay)
  - eKYC/CCCD (tuổi)
  - Điểm tín dụng CIC qua cic-service (cic_score, 150–750)
"""
```

- [ ] **Step 4: Chạy test — phải PASS**

```bash
pytest tests/test_features.py -v
```

Expected: 5 passed

- [ ] **Step 5: Commit**

```bash
git add app/ml/features.py tests/test_features.py
git commit -m "feat(ai): add cic_score to ML feature pipeline"
```

---

### Task 4: Predictor — tích hợp CIC score vào `chuan_bi_dac_trung()` và `du_doan()`

**Files:**
- Modify: `app/ml/predictor.py`
- Modify: `app/api/credit_router.py`
- Test: `tests/test_cic_integration.py`

**Interfaces:**
- Consumes: `CicClient.tra_diem_cic(so_cccd) -> int | None` (Task 1), `FEATURE_NAMES` mới (Task 3)
- Produces: `BoDuDoan.du_doan(ho_so, cic_score=None) -> dict` — dict có thêm key `"cic_score"`

- [ ] **Step 1: Viết test tích hợp**

Tạo `tests/test_cic_integration.py`:

```python
"""Test tích hợp: cic_score đi qua pipeline chuan_bi_dac_trung()."""
import numpy as np
import pytest

from app.ml.predictor import BoDuDoan


class TestChuanBiDacTrungVoiCic:
    """chuan_bi_dac_trung() xử lý cic_score đúng."""

    @pytest.fixture
    def ho_so_co_ban(self):
        return {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
            "person_age": 30,
            "emp_length": "5 years",
            "int_rate": 0.15,
            "term_months": 12,
            "dti": 15.5,
        }

    def test_cic_score_duoc_giu_khi_co(self, ho_so_co_ban):
        """Khi truyền cic_score, giá trị được giữ nguyên trong feature dict."""
        ho_so_co_ban["cic_score"] = 580
        # Chỉ test chuan_bi_dac_trung nếu có model. Nếu không có, skip.
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model chưa được train với cic_score")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["cic_score"] == 580
        assert row["cic_score_missing"] == 0.0

    def test_cic_score_missing_khi_none(self, ho_so_co_ban):
        """Khi không có cic_score, missing indicator = 1.0 và dùng median."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model chưa được train với cic_score")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["cic_score_missing"] == 1.0
        # cic_score phải được điền bằng median, không phải None
        assert row["cic_score"] is not None
        assert not (isinstance(row["cic_score"], float) and np.isnan(row["cic_score"]))


class TestDuDoanVoiCic:
    """du_doan() trả cic_score trong kết quả."""

    def test_ket_qua_co_cic_score(self):
        """Kết quả dict có key cic_score."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model chưa được train với cic_score")
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
        }
        ket_qua = bo.du_doan(ho_so, cic_score=580)
        assert "cic_score" in ket_qua
        assert ket_qua["cic_score"] == 580

    def test_ket_qua_cic_score_none(self):
        """Khi không tra được CIC, cic_score trong kết quả = None."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model chưa được train với cic_score")
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
        }
        ket_qua = bo.du_doan(ho_so, cic_score=None)
        assert ket_qua["cic_score"] is None
```

- [ ] **Step 2: Chạy test — phải FAIL**

```bash
pytest tests/test_cic_integration.py -v
```

Expected: FAIL — `du_doan()` chưa nhận `cic_score` parameter

- [ ] **Step 3: Sửa `app/ml/predictor.py` — `chuan_bi_dac_trung()` xử lý `cic_score`**

Trong method `chuan_bi_dac_trung()`, thêm `cic_score` vào dict `row` (sau dòng `"interest_method"`):

```python
            "interest_method": ho_so.get("interest_method") or PHUONG_PHAP_MAC_DINH,
            "cic_score": ho_so.get("cic_score"),
```

Không cần sửa gì thêm trong `chuan_bi_dac_trung()` — vòng lặp missing indicator và median fill đã xử lý tự động nhờ `cic_score` đã nằm trong `COLUMNS_WITH_MISSING` (Task 3).

- [ ] **Step 4: Sửa `app/ml/predictor.py` — `du_doan()` nhận và truyền `cic_score`**

Sửa signature của `du_doan()` và thêm `cic_score` vào kết quả:

```python
    def du_doan(self, ho_so: dict, cic_score: int | None = None) -> dict:
        """Chấm điểm đầy đủ: mô hình → rule engine → quyết định."""
        if cic_score is not None:
            ho_so = {**ho_so, "cic_score": cic_score}

        pd_probability = self.du_doan_pd(ho_so)
        risk_score = tinh_diem_rui_ro(ho_so)
        evaluation_score = tinh_diem_tong_hop(pd_probability, risk_score)
        hang = xep_hang(evaluation_score)

        chot_chan_ly_do = kiem_tra_chot_chan_cung(ho_so)

        return {
            "pd_probability": round(pd_probability, 4),
            "risk_score": risk_score,
            "evaluation_score": round(evaluation_score, 2),
            "credit_grade": hang.hang,
            "suggested_limit": hang.han_muc,
            "decision": quyet_dinh(evaluation_score, chot_chan_ly_do),
            "rejection_reason": chot_chan_ly_do,
            "model_version": self.metadata["version"],
            "cic_score": cic_score,
        }
```

Cập nhật docstring file (`predictor.py` dòng đầu): bỏ đoạn nói "Không còn cổng chặn CIC", thay bằng:

```python
"""
Bộ dự đoán dùng gói model tự chứa (`models/model_v<PHIEN_BAN_MAC_DINH>.pkl` + `.json`).

Trường thiếu trong hồ sơ được điền bằng **median lưu trong gói** — đúng giá trị mà
mô hình đã học lúc huấn luyện — chứ không phải hằng số viết cứng trong code.

Điểm CIC được lấy từ cic-service qua HTTP. Khi cic-service không khả dụng,
cic_score = None → dùng median từ gói model + missing indicator.

Gói được tạo bởi `scripts/train_final_model.py`.
"""
```

- [ ] **Step 5: Sửa `app/api/credit_router.py` — gọi CIC client**

Thay toàn bộ nội dung `credit_router.py`:

```python
"""
Router cho API Chấm điểm Tín dụng (Credit Scoring).

Luồng ra quyết định:

    Hồ sơ vay + CCCD
        ├──→ cic-service (HTTP) ──→ cic_score (150-750) hoặc None
        │
        ├──────────────────────────┬──────────────────────────┐
        ▼                          ▼                          │
    Mô hình XGBoost             Rule Engine 5C                │
    (có cic_score) → PD         (4 yếu tố) → risk_score       │
        └──────────────────────────┴──────────────────────────┘
                                  ▼
            evaluation_score = (1-PD)x100 x 0,6 + risk_score x 0,4
                                  ▼
                    credit_grade · decision · hạn mức

TODO: /explain (SHAP), /backtest.
"""
from functools import lru_cache

from fastapi import APIRouter, HTTPException, status

from app.ml.predictor import BoDuDoan
from app.schemas.credit import CreditScoreRequest, CreditScoreResponse
from app.services.cic_client import CicClient

router = APIRouter()


@lru_cache(maxsize=1)
def lay_bo_du_doan() -> BoDuDoan:
    """Nạp gói model một lần rồi dùng lại.

    Gói nặng ~1,9MB; nạp lại mỗi request sẽ tốn vô ích. `lru_cache` giữ instance
    trong suốt vòng đời tiến trình. Muốn nạp lại sau khi huấn luyện mô hình mới:
    khởi động lại service, hoặc gọi `lay_bo_du_doan.cache_clear()`.
    """
    return BoDuDoan.nap()


@lru_cache(maxsize=1)
def lay_cic_client() -> CicClient:
    """CicClient singleton — cấu hình qua env CIC_SERVICE_URL."""
    return CicClient()


@router.post("/score", response_model=CreditScoreResponse)
async def score_credit(ho_so: CreditScoreRequest) -> CreditScoreResponse:
    """Chấm điểm tín dụng cho một hồ sơ vay."""
    try:
        bo_du_doan = lay_bo_du_doan()
    except (FileNotFoundError, ValueError) as loi:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "MODEL_NOT_AVAILABLE",
                "message": "Không nạp được gói model chấm điểm.",
                "details": [str(loi)],
            },
        ) from loi

    # Tra điểm CIC nếu có CCCD
    cic_score: int | None = None
    if ho_so.so_cccd:
        cic_client = lay_cic_client()
        cic_score = await cic_client.tra_diem_cic(ho_so.so_cccd)

    ket_qua = bo_du_doan.du_doan(
        ho_so.model_dump(exclude_none=True),
        cic_score=cic_score,
    )
    return CreditScoreResponse(**ket_qua)
```

- [ ] **Step 6: Chạy test — phải PASS**

```bash
pytest tests/ -v
```

Expected: tất cả test pass (test tích hợp có thể skip nếu chưa có model v11)

- [ ] **Step 7: Commit**

```bash
git add app/ml/predictor.py app/api/credit_router.py tests/test_cic_integration.py
git commit -m "feat(ai): integrate CIC score into ML prediction pipeline"
```

---

### Task 5: Cập nhật docstrings và comments xuyên suốt codebase

**Files:**
- Modify: `app/services/rule_engine.py` (docstring only)
- Modify: `app/ml/preprocessing.py` (docstring only)

**Interfaces:**
- Consumes: không
- Produces: không (chỉ documentation)

- [ ] **Step 1: Cập nhật docstring `rule_engine.py`**

Sửa docstring module (dòng 1–17) — cập nhật bảng nguồn dữ liệu:

```python
"""
Rule Engine — chấm điểm rủi ro bằng quy tắc tường minh, chạy song song với mô hình ML.

Đây là phần minh bạch nhất của hệ thống: ngưỡng cố định, không học từ dữ liệu, giải
trình được với người vay và với kiểm toán mà không cần công cụ nào.

Bốn yếu tố hiện tại đều tính được từ hồ sơ tự khai + eKYC:

| Yếu tố | Nhóm 5C | Nguồn dữ liệu |
|---|---|---|
| Tỷ lệ vay trên thu nhập | Capacity | Form vay + thu nhập tự khai |
| Thâm niên việc làm | Character | Hợp đồng lao động |
| Tình trạng nhà ở | Capital | Tự khai |
| Mức thu nhập năm | Capacity | Sao kê lương |

Lưu ý: Điểm CIC (cic_score) được dùng trong mô hình ML, KHÔNG dùng trong rule engine.

Khoảng điểm AI, hạng tín dụng, hạn mức và ngưỡng duyệt được đọc từ
config/product_config.json — xem app/services/product_config.py.
"""
```

- [ ] **Step 2: Cập nhật docstring `preprocessing.py`**

Sửa docstring module (dòng 1–9):

```python
"""
Chuẩn hóa dữ liệu thô về schema chung.

Các hàm ở đây được dùng ở CẢ HAI nơi — lúc huấn luyện (`scripts/train_final_model.py`)
và lúc chấm điểm hồ sơ thật (`app/ml/predictor.py`). Đây là điều bắt buộc: nếu hai
bên tự chuẩn hóa theo cách riêng thì cùng một hồ sơ sẽ cho hai kết quả khác nhau.

Điểm CIC (cic_score) đi thẳng vào pipeline ML mà không cần chuẩn hóa ở đây —
giá trị 150–750 từ cic-service đã ở đúng thang cần dùng.
"""
```

- [ ] **Step 3: Commit**

```bash
git add app/services/rule_engine.py app/ml/preprocessing.py
git commit -m "docs(ai): update docstrings to reflect CIC integration"
```

---

## Lưu ý: Model cần retrain

Sau khi hoàn thành 5 task trên, **cơ sở hạ tầng code đã sẵn sàng**. Tuy nhiên:

- Model hiện tại (`v10.0.0`) có 28 features, **không** chứa `cic_score`
- `BoDuDoan.nap()` sẽ raise `ValueError` vì `metadata["feature_names"] != FEATURE_NAMES`
- Cần chạy `scripts/train_final_model.py` với dữ liệu có `cic_score` để tạo model `v11.0.0`
- Khi train, `cic_score` cần có trong training data (hoặc dùng giá trị giả lập) để model học được feature này
- Sau khi train xong, sửa `PHIEN_BAN_MAC_DINH = "11.0.0"` trong `predictor.py`

Đây là bước riêng biệt, nằm ngoài scope của plan này.
