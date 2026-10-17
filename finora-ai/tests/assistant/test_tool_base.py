"""Khai báo tool: schema gửi LLM, kiểm tham số, rule allowlist, helper run_tool."""

import re
from typing import Literal

import pytest
from pydantic import BaseModel, Field, ValidationError

from app.services.assistant.llm.providers import Lane
from app.services.assistant.principal import Toolset
from app.services.assistant.relay import FinoraApiClient, ServiceName
from app.services.assistant.tools.base import (
    NoParams,
    RouteRule,
    ToolError,
    ToolParams,
    ToolResult,
    ToolSpec,
)

from .helpers import run_tool


class LocKhoanParams(ToolParams):
    grade: Literal["A", "B", "C", "D", "E"] | None = Field(default=None, description="Hạng giá")
    min_rate: float | None = Field(default=None, ge=0, le=100, description="Lãi tối thiểu %/năm")
    max_term_months: int | None = Field(default=None, ge=1, le=60)


class ChiTietParams(ToolParams):
    application_number: str = Field(pattern=r"^LA-[A-Z0-9]{6,32}$", description="Mã hồ sơ")


async def _khong_lam_gi(client: FinoraApiClient, params: BaseModel) -> ToolResult:
    return ToolResult(facts={})


def _spec(name="khoan_dang_goi_von", params_model=LocKhoanParams, handler=_khong_lam_gi, routes=()):
    return ToolSpec(
        name=name,
        toolset=Toolset.INVESTOR,
        description="Liệt kê khoản đang gọi vốn theo hạn đóng.",
        params_model=params_model,
        handler=handler,
        lane=Lane.USER_DATA,
        routes=routes,
    )


class TestOpenAiSchema:
    def test_tham_so_tuy_chon_camel_case_phang(self):
        assert _spec().openai_schema() == {
            "type": "function",
            "function": {
                "name": "khoan_dang_goi_von",
                "description": "Liệt kê khoản đang gọi vốn theo hạn đóng.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "grade": {"type": "string", "enum": ["A", "B", "C", "D", "E"], "description": "Hạng giá"},
                        "minRate": {
                            "type": "number",
                            "minimum": 0,
                            "maximum": 100,
                            "description": "Lãi tối thiểu %/năm",
                        },
                        "maxTermMonths": {"type": "integer", "minimum": 1, "maximum": 60},
                    },
                },
            },
        }

    def test_tham_so_bat_buoc_nam_trong_required(self):
        parameters = _spec(name="chi_tiet_ho_so", params_model=ChiTietParams).openai_schema()["function"]["parameters"]
        assert parameters["required"] == ["applicationNumber"]
        assert parameters["properties"]["applicationNumber"] == {
            "type": "string",
            "pattern": "^LA-[A-Z0-9]{6,32}$",
            "description": "Mã hồ sơ",
        }

    def test_tool_khong_tham_so(self):
        parameters = _spec(name="vi_cua_toi", params_model=NoParams).openai_schema()["function"]["parameters"]
        assert parameters == {"type": "object", "properties": {}, "additionalProperties": False}

    def test_tu_choi_tham_so_long_nhau(self):
        class Con(BaseModel):
            x: int

        class Long(ToolParams):
            con: Con

        with pytest.raises(ValueError, match="phẳng"):
            _spec(params_model=Long).openai_schema()


class TestParams:
    def test_nhan_camel_case_va_tu_choi_khoa_thua(self):
        assert LocKhoanParams.model_validate({"minRate": 12.5}).min_rate == 12.5
        with pytest.raises(ValidationError):
            LocKhoanParams.model_validate({"minRate": 12.5, "userId": "u-2"})

    def test_no_params_tu_choi_moi_khoa(self):
        NoParams.model_validate({})
        with pytest.raises(ValidationError):
            NoParams.model_validate({"borrowerId": "u-2"})


class TestKhaiBao:
    def test_ten_tool_sai_bi_tu_choi(self):
        with pytest.raises(ValueError):
            _spec(name="Hồ sơ của tôi")

    def test_route_chi_get_post(self):
        with pytest.raises(ValueError):
            RouteRule("DELETE", ServiceName.LOAN, r"/api/v1/x")

    def test_route_pattern_sai_lo_ngay(self):
        with pytest.raises(re.error):
            RouteRule("GET", ServiceName.LOAN, r"/api/v1/(")

    def test_route_khop_toan_bo_path(self):
        rule = RouteRule("GET", ServiceName.LOAN, r"/api/v1/loans/me")
        assert rule.matches("GET", ServiceName.LOAN, "/api/v1/loans/me")
        assert not rule.matches("GET", ServiceName.LOAN, "/api/v1/loans/me/extra")
        assert not rule.matches("GET", ServiceName.LOAN, "/x/api/v1/loans/me")

    def test_tool_error_giu_ma_va_cau(self):
        error = ToolError("KHONG_TIM_THAY", "Không tìm thấy", upstream_code="APPLICATION_NOT_FOUND")
        assert (error.code, error.user_message, error.upstream_code) == (
            "KHONG_TIM_THAY", "Không tìm thấy", "APPLICATION_NOT_FOUND",
        )
        assert ToolError("X", "y").upstream_code is None


class TestRunTool:
    @pytest.mark.asyncio
    async def test_chay_tool_qua_relay_gia(self):
        async def handler(client: FinoraApiClient, params: ChiTietParams) -> ToolResult:
            data = await client.get(ServiceName.LOAN, f"/api/v1/loan-applications/{params.application_number}")
            return ToolResult(facts={"trang_thai": data["status"]}, ids=frozenset({params.application_number}))

        spec = _spec(
            name="chi_tiet_ho_so",
            params_model=ChiTietParams,
            handler=handler,
            routes=(RouteRule("GET", ServiceName.LOAN, r"/api/v1/loan-applications/LA-[A-Z0-9]{6,32}"),),
        )

        result = await run_tool(
            spec,
            {"applicationNumber": "LA-ABC123"},
            {("GET", "http://loan.test/api/v1/loan-applications/LA-ABC123"): (200, {"status": "APPROVED"})},
        )

        assert result.facts == {"trang_thai": "APPROVED"}
        assert result.ids == frozenset({"LA-ABC123"})

    @pytest.mark.asyncio
    async def test_url_chua_khai_bao_lam_test_hong(self):
        async def handler(client: FinoraApiClient, params: NoParams) -> ToolResult:
            await client.get(ServiceName.LOAN, "/api/v1/loans/me")
            return ToolResult(facts={})

        spec = _spec(
            name="khoan_vay_cua_toi",
            params_model=NoParams,
            handler=handler,
            routes=(RouteRule("GET", ServiceName.LOAN, r"/api/v1/loans/me"),),
        )

        with pytest.raises(AssertionError, match="chưa khai báo"):
            await run_tool(spec, {}, {})

    @pytest.mark.asyncio
    async def test_tham_so_sai_bi_tu_choi_truoc_khi_goi(self):
        spec = _spec(name="chi_tiet_ho_so", params_model=ChiTietParams)
        with pytest.raises(ValidationError):
            await run_tool(spec, {"applicationNumber": "../admin"}, {})
