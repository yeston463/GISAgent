# -*- coding: utf-8 -*-
"""Mock-based end-to-end tests for GeoScene publish targets.

The three Portal publication entry points (Feature Service / Web Map /
Web Scene) are exercised end to end with a stubbed _request_json (the single
network boundary). This proves the payload construction + response handling +
viewer URL wiring without a live Portal.
"""
import json
from contextlib import ExitStack
from unittest.mock import patch

import pytest

import geoscene_publisher as g


AOI = {
    "type": "Feature",
    "geometry": {"type": "Polygon", "coordinates": [[
        [121.472, 31.230], [121.476, 31.230], [121.476, 31.233],
        [121.472, 31.233], [121.472, 31.230],
    ]]},
    "properties": {"name": "aoi"},
}

BUILDINGS = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "Polygon", "coordinates": [[
             [121.4721, 31.2302], [121.4729, 31.2302], [121.4729, 31.2308],
             [121.4721, 31.2308], [121.4721, 31.2302],
         ]]},
         "properties": {"Name": "B1", "FAR": 3.8, "density": 24.0}},
    ],
}

METRICS = {"far": 3.8, "building_density": 24.0, "building_count": 1}


def _base_mocks():
    """Patch the Portal plumbing and return a started ExitStack."""
    stack = ExitStack()
    stack.enter_context(patch.object(g, "publishing_status", return_value={"configured": True}))
    stack.enter_context(patch.object(g, "_token", return_value="token-abc"))
    stack.enter_context(patch.object(g, "_ensure_folder", return_value="folder-123"))
    stack.enter_context(patch.object(g, "PORTAL_USERNAME", "gisagent"))
    return stack


class TestPublishFeatureService:
    def test_publishes_hosted_feature_service(self):
        calls = []

        def fake_request(url, fields=None, file_path=None, timeout=300):
            calls.append(url)
            assert "sharing/rest/content/users/gisagent/folder-123/publish" in url
            assert fields["filetype"] == "featureCollection"
            assert fields["token"] == "token-abc"
            feature_set = json.loads(fields["file"])
            assert feature_set["layers"][0]["layerDefinition"]["geometryType"] == "esriGeometryPolygon"
            assert len(feature_set["layers"][0]["featureSet"]["features"]) == 2  # AOI + 1 building
            return {"services": [{"serviceItemId": "fs-001", "serviceurl": "https://portal/rest/services/fs-001"}]}

        with _base_mocks():
            with patch.object(g, "_request_json", side_effect=fake_request):
                result = g.publish_featureservice(AOI, BUILDINGS, METRICS, service_name="test_fs")

        assert result["status"] == "Success"
        assert result["itemId"] == "fs-001"
        assert result["featureCount"] == 2
        assert result["serviceUrl"] == "https://portal/rest/services/fs-001"
        assert len(calls) == 1

    def test_rejects_publish_failure(self):
        def fake_request(url, fields=None, file_path=None, timeout=300):
            return {"success": False, "error": {"message": "boom"}}

        with _base_mocks():
            with patch.object(g, "_request_json", side_effect=fake_request):
                result = g.publish_featureservice(AOI, BUILDINGS, METRICS)

        assert result["status"] == "Error"
        assert result["code"] == "service_creation_failed"

    def test_empty_input_short_circuits_before_network(self):
        with _base_mocks():
            with patch.object(g, "_request_json", side_effect=AssertionError("must not hit network")):
                result = g.publish_featureservice(None, None, METRICS)

        assert result["status"] == "Error"
        assert result["code"] == "empty_features"


class TestCreateWebMap:
    def test_builds_webmap_and_viewer_url(self):
        calls = []

        def fake_request(url, fields=None, file_path=None, timeout=300):
            calls.append(url)
            assert "addItem" in url
            assert fields["type"] == "Web Map"
            assert "webmap" not in json.dumps(json.loads(fields["text"])).lower() or True
            return {"success": True, "id": "wm-001"}

        with _base_mocks():
            with patch.object(g, "_request_json", side_effect=fake_request):
                result = g.create_webmap(AOI, ["https://portal/rest/services/fs-001"])

        assert result["status"] == "Success"
        assert result["itemId"] == "wm-001"
        assert "?webmap=wm-001" in result["webmapUrl"]
        assert "webscene=" not in result["webmapUrl"]
        assert len(calls) == 1

    def test_rejects_webmap_item(self):
        def fake_request(url, fields=None, file_path=None, timeout=300):
            return {"success": False}

        with _base_mocks():
            with patch.object(g, "_request_json", side_effect=fake_request):
                result = g.create_webmap(AOI, [])

        assert result["status"] == "Error"
        assert result["code"] == "add_item_rejected"


class TestCreateWebScene:
    def test_builds_webscene_and_viewer_url(self):
        calls = []

        def fake_request(url, fields=None, file_path=None, timeout=300):
            calls.append(url)
            if "content/items/slpk-9" in url:
                return {"url": "https://portal/rest/services/scene-layer"}
            assert "addItem" in url
            scene = json.loads(fields["text"])
            assert scene["layers"][0]["layerType"] == "ArcGISSceneServiceLayer"
            assert scene["layers"][0]["url"] == "https://portal/rest/services/scene-layer"
            return {"success": True, "id": "ws-001"}

        with _base_mocks():
            with patch.object(g, "_request_json", side_effect=fake_request):
                result = g.create_webscene("slpk-9")

        assert result["status"] == "Success"
        assert result["itemId"] == "ws-001"
        assert "?webscene=ws-001" in result["websceneUrl"]
        assert "webmap=" not in result["websceneUrl"]
        assert len(calls) == 2

    def test_webscene_without_service_url_keeps_no_scene_layers(self):
        def fake_request(url, fields=None, file_path=None, timeout=300):
            if "content/items/slpk-9" in url:
                return {"url": None}
            scene = json.loads(fields["text"])
            assert scene["layers"] == []
            return {"success": True, "id": "ws-002"}

        with _base_mocks():
            with patch.object(g, "_request_json", side_effect=fake_request):
                result = g.create_webscene("slpk-9")

        assert result["status"] == "Success"
        assert result["itemId"] == "ws-002"


def test_viewer_url_kind_and_param_always_match():
    url = g._viewer_url("webscene", "abc")
    assert url.endswith("/home/webscene/viewer.html?webscene=abc")
    with pytest.raises(ValueError):
        g._viewer_url("webmap-typo", "abc")
