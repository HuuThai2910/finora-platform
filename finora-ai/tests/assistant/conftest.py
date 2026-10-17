"""Fixture chung cho test của trợ lý."""

import pytest

from app.services.assistant.settings import AssistantSettings

from .helpers import make_settings


@pytest.fixture
def settings() -> AssistantSettings:
    """Cấu hình test: bật trợ lý, đủ ba vai trò, URL service `*.test`."""
    return make_settings()
