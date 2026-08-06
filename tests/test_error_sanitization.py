# -*- coding: utf-8 -*-
"""Verify the FastAPI error sanitiser does not leak internal exception detail.

Security requirement: when the service layer raises, the client must receive a
generic message and never the exception traceback, file paths or credentials.
"""
from fastapi.testclient import TestClient

from gis.router import app, _server_error

client = TestClient(app)


def test_server_error_payload_is_sanitized():
    exc = ValueError("psycopg2.OperationalError: password authentication failed for user 'postgres'")
    payload = _server_error("urban_metrics", exc, far=0)
    assert payload["status"] == "Error"
    assert payload["stage"] == "urban_metrics"
    assert payload["far"] == 0
    # 必须不包含内部细节
    assert "password" not in payload["message"]
    assert "postgres" not in payload["message"]
    assert "psycopg2" not in payload["message"]
    # 仅返回通用提示
    assert payload["message"] == "服务内部错误，请稍后重试。"


def test_invalid_job_id_returns_controlled_400():
    """ValueError 校验路径应返回明确、受控的 400（非内部泄露）。"""
    response = client.get("/analysis/cityengine/jobs/../../etc/passwd")
    # 不存在的作业：ValueError 校验后返回 400（受控消息），非 500
    assert response.status_code in (400, 404)
    assert "Traceback" not in response.text