# -*- coding: utf-8 -*-
import json

import geoscene_publisher as publisher


def test_poll_item_status_waits_for_partial(monkeypatch):
    responses = iter([
        {"status": "partial", "jobInfo": {"jobId": "publish-1"}},
        {"status": "processing"},
        {"status": "completed"},
    ])
    monkeypatch.setattr(publisher, "_request_json", lambda *args, **kwargs: next(responses))
    monkeypatch.setattr(publisher.time, "sleep", lambda _seconds: None)

    result = publisher._poll_item_status("token", "service-item", poll_interval=0)

    assert result["status"] == "completed"


def test_find_existing_service_ignores_orphan_portal_item(monkeypatch):
    item = {
        "id": "orphan-item",
        "title": "ce-job",
        "type": "Scene Service",
        "url": "https://example.test/SceneServer",
    }

    def fail_metadata(*_args, **_kwargs):
        raise RuntimeError("404 Service not found")

    monkeypatch.setattr(publisher, "_read_scene_service", fail_metadata)

    assert publisher._find_existing_service("token", "ce-job", [item]) is None


def test_find_existing_service_requires_real_scene_metadata(monkeypatch):
    item = {
        "id": "service-item",
        "title": "ce-job",
        "type": "Scene Service",
        "url": "https://example.test/SceneServer",
    }
    monkeypatch.setattr(
        publisher,
        "_read_scene_service",
        lambda url, token=None: {
            "url": url,
            "serviceName": "ce_job",
            "serviceType": "SceneServer",
            "verified": True,
        },
    )

    result = publisher._find_existing_service("token", "ce-job", [item])

    assert result["serviceItemId"] == "service-item"
    assert result["hostedService"]["verified"] is True


def test_publish_uses_fresh_name_for_orphan_and_verifies_before_success(tmp_path, monkeypatch):
    slpk = tmp_path / "result.slpk"
    slpk.write_bytes(b"slpk")
    events = []
    publish_parameters = {}

    monkeypatch.setattr(publisher, "publishing_status", lambda: {"configured": True})
    monkeypatch.setattr(publisher, "_token", lambda: "token")
    monkeypatch.setattr(publisher, "_ensure_folder", lambda _token: "folder")
    monkeypatch.setattr(publisher, "_poll_item_status", lambda *args, **kwargs: {"status": "completed"})
    monkeypatch.setattr(
        publisher,
        "verify_scene_service",
        lambda url, timeout=90, token=None, poll_interval=3: {
            "url": url,
            "serviceName": "fresh",
            "serviceType": "SceneServer",
            "verified": True,
        },
    )
    monkeypatch.setattr(publisher, "share_publication", lambda publication: publication)
    monkeypatch.setattr(publisher, "_find_existing_service", lambda *args, **kwargs: None)

    def request_json(url, fields=None, file_path=None, timeout=300):
        if url.endswith("/search") and fields.get("type") is None:
            query = fields.get("q", "")
            if 'type:"Scene Package"' in query:
                return {"results": [{"id": "source-item", "title": "ce-job", "type": "Scene Package"}]}
            return {
                "results": [{
                    "id": "orphan-item",
                    "title": "ce-job",
                    "type": "Scene Service",
                    "url": "https://example.test/orphan/SceneServer",
                }]
            }
        if url.endswith("/publish"):
            publish_parameters.update(json.loads(fields["publishParameters"]))
            return {"services": [{
                "success": True,
                "serviceItemId": "fresh-item",
                "serviceUrl": "https://example.test/fresh/SceneServer",
            }]}
        raise AssertionError(f"Unexpected request: {url}")

    monkeypatch.setattr(publisher, "_request_json", request_json)

    result = publisher.publish_slpk(
        slpk,
        "ce-job",
        lambda stage, status, message, details: events.append((stage, status, details)),
    )

    assert publish_parameters["name"].startswith("ce_job_retry_")
    assert result["serviceItemId"] == "fresh-item"
    assert result["hostedService"]["verified"] is True
    assert events[-1][0:2] == ("scene_published", "success")
