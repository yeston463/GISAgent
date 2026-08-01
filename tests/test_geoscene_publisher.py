# -*- coding: utf-8 -*-
import json
import os
from types import SimpleNamespace

import pytest

import geoscene_publisher as publisher


def test_load_env_file_uses_file_values_without_overriding_process_environment(tmp_path, monkeypatch):
    env_file = tmp_path / ".env"
    env_file.write_text(
        "# local settings\nGEOSCENE_PORTAL_USERNAME=file-user\nGEOSCENE_PORTAL_PASSWORD='file-password'\n",
        encoding="utf-8",
    )
    monkeypatch.delenv("GEOSCENE_PORTAL_USERNAME", raising=False)
    monkeypatch.setenv("GEOSCENE_PORTAL_PASSWORD", "process-password")

    publisher._load_env_file(env_file)

    assert os.environ["GEOSCENE_PORTAL_USERNAME"] == "file-user"
    assert os.environ["GEOSCENE_PORTAL_PASSWORD"] == "process-password"


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


def test_cancel_publish_job_uses_cancel_endpoint(monkeypatch):
    calls = []

    def request_json(url, fields=None, file_path=None, timeout=300):
        calls.append((url, fields, timeout))
        return {"success": True}

    monkeypatch.setattr(publisher, "_request_json", request_json)

    assert publisher._cancel_publish_job(
        "https://example.test/arcgis/rest/services/System/PublishingTools/GPServer/Publish+Portal+Service/jobs/j1/status",
        "token",
    )
    assert calls == [(
        "https://example.test/arcgis/rest/services/System/PublishingTools/GPServer/Publish+Portal+Service/jobs/j1/cancelJob",
        {"token": "token", "f": "json"},
        30,
    )]


def test_object_store_descriptor_parses_describedatastore(monkeypatch):
    monkeypatch.delenv("GEOSCENE_OBJECT_STORE_ID", raising=False)
    monkeypatch.delenv("GEOSCENE_OBJECT_STORE_MACHINE", raising=False)
    monkeypatch.setattr(
        publisher,
        "_describe_datastore",
        lambda: """
Information for object store oz_8yqgylo
==================================================
Registered machines.................PRODUCT.GEOSCENEENTERPRISE.CN
""",
    )

    assert publisher._object_store_descriptor() == {
        "id": "oz_8yqgylo",
        "machine": "PRODUCT.GEOSCENEENTERPRISE.CN",
    }


def test_describe_datastore_runs_tool_path_with_spaces_directly(tmp_path, monkeypatch):
    tool = tmp_path / "Program Files" / "GeoScene" / "DataStore" / "tools" / "describedatastore.bat"
    tool.parent.mkdir(parents=True)
    tool.write_text("@echo off", encoding="utf-8")
    calls = []

    def fake_run(command, **kwargs):
        calls.append(command)
        return SimpleNamespace(stdout="Information for object store oz_test\n", stderr="", returncode=0)

    monkeypatch.setattr(publisher, "DATASTORE_DESCRIBE_TOOL", tool)
    monkeypatch.setattr(publisher.subprocess, "run", fake_run)

    assert "oz_test" in publisher._describe_datastore()
    assert calls[0] == [str(tool)]


def test_object_store_descriptor_can_use_startup_health_log(tmp_path, monkeypatch):
    log_file = tmp_path / "geoscene-services.log"
    log_file.write_text(
        "[2026-07-31 16:57:00] Object store validate healthy: "
        "id=oz_log, machine=PRODUCT.GEOSCENEENTERPRISE.CN, overallhealth=Healthy\n",
        encoding="utf-8",
    )
    monkeypatch.delenv("GEOSCENE_OBJECT_STORE_ID", raising=False)
    monkeypatch.delenv("GEOSCENE_OBJECT_STORE_MACHINE", raising=False)
    monkeypatch.setattr(publisher, "SERVICES_LOG_FILE", log_file)
    monkeypatch.setattr(
        publisher,
        "_describe_datastore",
        lambda: (_ for _ in ()).throw(AssertionError("describedatastore should not be required")),
    )

    assert publisher._object_store_descriptor() == {
        "id": "oz_log",
        "machine": "PRODUCT.GEOSCENEENTERPRISE.CN",
    }


def test_latest_ozone_recovery_time_reads_recent_retry(tmp_path, monkeypatch):
    ozone_log = tmp_path / "ozone.log"
    ozone_log.write_text(
        "\n".join([
            "2026-07-31 18:47:50,000 [pool] INFO healthy line",
            "2026-07-31 18:47:53,610 [pool] ERROR org.apache.ratis.client.impl.OrderedAsync: Failed to send request",
            "java.util.concurrent.CompletionException: org.apache.ratis.protocol.exceptions.RaftRetryFailureException: boom",
        ]),
        encoding="utf-8",
    )
    monkeypatch.setattr(publisher, "DATASTORE_OZONE_LOG", ozone_log)

    assert publisher._latest_ozone_recovery_time().strftime("%Y-%m-%d %H:%M:%S") == "2026-07-31 18:47:53"


def test_wait_object_store_quiet_returns_immediately_without_recent_retry(tmp_path, monkeypatch):
    ozone_log = tmp_path / "ozone.log"
    ozone_log.write_text(
        "2026-07-31 18:47:50,000 [pool] INFO healthy line\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(publisher, "DATASTORE_OZONE_LOG", ozone_log)

    quiet = publisher.wait_object_store_quiet(timeout=1, quiet_seconds=1, poll_interval=0)

    assert quiet == {"quietForSeconds": None, "lastErrorTime": None}


def test_discover_stale_publications_limits_scope(monkeypatch):
    now_ms = 2_000_000
    search_results = [
        {
            "id": "stale-item",
            "title": "gisagent_smoke_old",
            "type": "Scene Service",
            "modified": 1_000_000,
            "url": "https://example.test/stale/SceneServer",
        },
        {
            "id": "recent-item",
            "title": "ce-20260731123456-abcd",
            "type": "Scene Service",
            "modified": 1_900_000,
            "url": "https://example.test/recent/SceneServer",
        },
        {
            "id": "manual-item",
            "title": "manual_scene_service",
            "type": "Scene Service",
            "modified": 1_000_000,
            "url": "https://example.test/manual/SceneServer",
        },
    ]

    def request_json(url, fields=None, file_path=None, timeout=300):
        if url.endswith("/search"):
            return {"results": search_results}
        if "/jobs/" in url:
            return {"jobId": "j-stale", "jobStatus": "esriJobExecuting"}
        raise AssertionError(f"Unexpected request: {url}")

    monkeypatch.setattr(publisher, "_request_json", request_json)
    monkeypatch.setattr(
        publisher,
        "_portal_item_status",
        lambda _token, item_id: {
            "status": "partial",
            "jobInfo": {"jobId": f"j-{item_id.removesuffix('-item')}"},
        },
    )
    monkeypatch.setattr(
        publisher,
        "_read_scene_service",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("404")),
    )

    stale = publisher.discover_stale_publications(
        "token",
        min_age_seconds=600,
        now_ms=now_ms,
    )

    assert len(stale) == 1
    assert stale[0]["itemId"] == "stale-item"
    assert stale[0]["jobId"] == "j-stale"
    assert stale[0]["ageSeconds"] == 1000


def test_cancel_stale_publications_can_delete_only_screened_items(monkeypatch):
    deleted_urls = []
    monkeypatch.setattr(publisher, "_cancel_publish_job", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(
        publisher,
        "_wait_publish_job_terminal",
        lambda *_args, **_kwargs: {
            "jobId": "j1",
            "jobStatus": "esriJobCancelled",
        },
    )

    def request_json(url, fields=None, file_path=None, timeout=300):
        deleted_urls.append(url)
        return {"success": True}

    monkeypatch.setattr(publisher, "_request_json", request_json)

    results = publisher.cancel_stale_publications(
        "token",
        [{
            "itemId": "partial-item",
            "title": "gisagent_smoke_old",
            "jobId": "j1",
            "jobUrl": "https://example.test/jobs/j1",
        }],
        delete_partial_items=True,
    )

    assert results[0]["cancelRequested"] is True
    assert results[0]["terminalJobStatus"] == "esriJobCancelled"
    assert results[0]["itemDeleted"] is True
    assert deleted_urls == [
        f"{publisher.PORTAL_URL}/sharing/rest/content/users/"
        f"{publisher.PORTAL_USERNAME}/items/partial-item/delete"
    ]


def test_cancel_stale_publications_admin_deletes_job_when_cancel_hangs(monkeypatch):
    monkeypatch.setattr(publisher, "_cancel_publish_job", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(
        publisher,
        "_wait_publish_job_terminal",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(TimeoutError("still executing")),
    )
    monkeypatch.setattr(publisher, "_delete_publish_job", lambda job_id, _token: job_id == "j1")

    results = publisher.cancel_stale_publications(
        "token",
        [{
            "itemId": "partial-item",
            "title": "gisagent_smoke_old",
            "jobId": "j1",
            "jobUrl": "https://example.test/rest/jobs/j1",
        }],
        delete_partial_items=False,
    )

    assert results[0]["cancelRequested"] is True
    assert results[0]["adminJobDeleted"] is True
    assert results[0]["error"] is None


def test_publish_fails_fast_when_stale_jobs_need_explicit_cleanup(tmp_path, monkeypatch):
    slpk = tmp_path / "result.slpk"
    slpk.write_bytes(b"slpk")
    monkeypatch.setattr(publisher, "publishing_status", lambda: {"configured": True})
    monkeypatch.setattr(publisher, "_token", lambda: "token")
    monkeypatch.setattr(publisher, "AUTO_CANCEL_STALE_PUBLICATIONS", False)
    monkeypatch.setattr(
        publisher,
        "discover_stale_publications",
        lambda _token: [{
            "itemId": "partial-item",
            "jobId": "j1",
        }],
    )
    monkeypatch.setattr(
        publisher,
        "wait_object_store_healthy",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("object-store wait must not start")
        ),
    )

    with pytest.raises(RuntimeError, match="j1"):
        publisher.publish_slpk(slpk, "ce-job")


def test_publish_checks_object_store_before_upload(tmp_path, monkeypatch):
    slpk = tmp_path / "result.slpk"
    slpk.write_bytes(b"slpk")
    order = []

    monkeypatch.setattr(publisher, "publishing_status", lambda: {"configured": True})
    monkeypatch.setattr(publisher, "_token", lambda: "token")
    monkeypatch.setattr(publisher, "discover_stale_publications", lambda _token: [])
    monkeypatch.setattr(publisher, "_ensure_folder", lambda _token: "folder")
    monkeypatch.setattr(publisher, "_find_service_items", lambda *_args, **_kwargs: [])
    monkeypatch.setattr(publisher, "_find_existing_service", lambda *args, **kwargs: None)
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
    monkeypatch.setattr(
        publisher,
        "wait_object_store_healthy",
        lambda *args, **kwargs: order.append("object-store") or {
            "descriptor": {"id": "oz_test", "machine": "HOST"},
            "validation": {"datastore.overallhealth": "Healthy"},
            "machine": {"name": "HOST"},
        },
    )
    monkeypatch.setattr(
        publisher,
        "wait_object_store_quiet",
        lambda *args, **kwargs: order.append("quiet") or {
            "quietForSeconds": 120,
            "lastErrorTime": "2026-07-31 18:30:00",
        },
    )

    def request_json(url, fields=None, file_path=None, timeout=300):
        if url.endswith("/search"):
            return {"results": []}
        if url.endswith("/addItem"):
            assert order == ["object-store", "quiet"]
            order.append("upload")
            return {"id": "source-item"}
        if url.endswith("/publish"):
            return {"services": [{
                "success": True,
                "serviceItemId": "fresh-item",
                "serviceUrl": "https://example.test/fresh/SceneServer",
            }]}
        raise AssertionError(f"Unexpected request: {url}")

    monkeypatch.setattr(publisher, "_request_json", request_json)

    result = publisher.publish_slpk(slpk, "ce-job")

    assert order == ["object-store", "quiet", "upload"]
    assert result["serviceItemId"] == "fresh-item"


def test_publish_cancels_background_job_when_item_stays_partial(tmp_path, monkeypatch):
    slpk = tmp_path / "result.slpk"
    slpk.write_bytes(b"slpk")
    cancel_calls = []

    monkeypatch.setattr(publisher, "publishing_status", lambda: {"configured": True})
    monkeypatch.setattr(publisher, "_token", lambda: "token")
    monkeypatch.setattr(publisher, "discover_stale_publications", lambda _token: [])
    monkeypatch.setattr(publisher, "_ensure_folder", lambda _token: "folder")
    monkeypatch.setattr(publisher, "_find_service_items", lambda *_args, **_kwargs: [])
    monkeypatch.setattr(publisher, "_find_existing_service", lambda *args, **kwargs: None)
    monkeypatch.setattr(publisher, "_poll", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        publisher,
        "wait_object_store_healthy",
        lambda *args, **kwargs: {
            "descriptor": {"id": "oz_test", "machine": "HOST"},
            "validation": {"datastore.overallhealth": "Healthy"},
            "machine": {"name": "HOST"},
        },
    )
    monkeypatch.setattr(
        publisher,
        "wait_object_store_quiet",
        lambda *args, **kwargs: {
            "quietForSeconds": 120,
            "lastErrorTime": "2026-07-31 18:30:00",
        },
    )
    monkeypatch.setattr(
        publisher,
        "_poll_item_status",
        lambda *args, **kwargs: (_ for _ in ()).throw(TimeoutError("status=partial")),
    )

    def request_json(url, fields=None, file_path=None, timeout=300):
        if url.endswith("/search"):
            return {"results": [{"id": "source-item", "title": "ce-job", "type": "Scene Package"}]}
        if url.endswith("/publish"):
            return {"services": [{
                "success": True,
                "statusURL": "https://example.test/gp/jobs/j1/status",
                "serviceItemId": "service-item",
                "serviceUrl": "https://example.test/Hosted/job/SceneServer",
            }]}
        if url.endswith("/cancelJob") or url.endswith("/cancel"):
            cancel_calls.append((url, fields))
            return {"success": True}
        raise AssertionError(f"Unexpected request: {url}")

    monkeypatch.setattr(publisher, "_request_json", request_json)

    with pytest.raises(RuntimeError, match="SceneServer 服务未生成"):
        publisher.publish_slpk(slpk, "ce-job")

    assert cancel_calls == [("https://example.test/gp/jobs/j1/cancelJob", {"token": "token", "f": "json"})]


def test_publish_uses_fresh_name_for_orphan_and_verifies_before_success(tmp_path, monkeypatch):
    slpk = tmp_path / "result.slpk"
    slpk.write_bytes(b"slpk")
    events = []
    publish_parameters = {}
    uploaded_sources = []

    monkeypatch.setattr(publisher, "publishing_status", lambda: {"configured": True})
    monkeypatch.setattr(publisher, "_token", lambda: "token")
    monkeypatch.setattr(publisher, "discover_stale_publications", lambda _token: [])
    monkeypatch.setattr(publisher, "_ensure_folder", lambda _token: "folder")
    monkeypatch.setattr(
        publisher,
        "wait_object_store_healthy",
        lambda *args, **kwargs: {
            "descriptor": {"id": "oz_test", "machine": "HOST"},
            "validation": {"datastore.overallhealth": "Healthy"},
            "machine": {"name": "HOST"},
        },
    )
    monkeypatch.setattr(
        publisher,
        "wait_object_store_quiet",
        lambda *args, **kwargs: {
            "quietForSeconds": 120,
            "lastErrorTime": "2026-07-31 18:30:00",
        },
    )
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
        if url.endswith("/addItem"):
            upload_path = type(slpk)(file_path)
            assert upload_path.is_file()
            uploaded_sources.append(upload_path.name)
            return {"id": "fresh-source-item"}
        raise AssertionError(f"Unexpected request: {url}")

    monkeypatch.setattr(publisher, "_request_json", request_json)

    result = publisher.publish_slpk(
        slpk,
        "ce-job",
        lambda stage, status, message, details: events.append((stage, status, details)),
    )

    assert publish_parameters["name"].startswith("ce_job_retry_")
    assert len(uploaded_sources) == 1
    assert uploaded_sources[0].startswith("result-retry-")
    assert uploaded_sources[0].endswith(".slpk")
    assert not list(tmp_path.glob("result-retry-*.slpk"))
    assert result["serviceItemId"] == "fresh-item"
    assert result["hostedService"]["verified"] is True
    assert events[-1][0:2] == ("scene_published", "success")
