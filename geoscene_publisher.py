# -*- coding: utf-8 -*-
import json
import mimetypes
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

PORTAL_URL = os.environ.get("GEOSCENE_PORTAL_URL", "https://product.geosceneenterprise.cn/geoscene").rstrip("/")
PORTAL_USERNAME = os.environ.get("GEOSCENE_PORTAL_USERNAME", "")
PORTAL_PASSWORD = os.environ.get("GEOSCENE_PORTAL_PASSWORD", "")
PORTAL_FOLDER = os.environ.get("GEOSCENE_PORTAL_FOLDER", "GISAgent-CityEngine")
VERIFY_SSL = os.environ.get("GEOSCENE_VERIFY_SSL", "false").lower() in {"1", "true", "yes"}


def _service_name(job_id):
    name = re.sub(r"[^A-Za-z0-9_]", "_", str(job_id)).strip("_")
    if not name:
        name = "cityengine_result"
    if name[0].isdigit():
        name = f"cityengine_{name}"
    return name[:120]


def _find_existing_service(token, job_id):
    search = _request_json(f"{PORTAL_URL}/sharing/rest/search", {
        "token": token,
        "f": "json",
        "q": f'owner:"{PORTAL_USERNAME}" AND title:"{job_id}" AND type:"Scene Service"',
        "num": 100,
    })
    item = next(
        (
            result
            for result in search.get("results", [])
            if result.get("title") == job_id and result.get("type") == "Scene Service"
        ),
        None,
    )
    if not item:
        return None
    service_url = item.get("url")
    if not service_url:
        details = _request_json(
            f"{PORTAL_URL}/sharing/rest/content/items/{item['id']}",
            {"token": token, "f": "json"},
        )
        service_url = details.get("url")
    if not service_url:
        return None
    return {"serviceItemId": item.get("id"), "sceneServiceUrl": service_url}


def _report_progress(callback, stage, status, message, **details):
    if callback:
        callback(stage, status, message, details)

def publishing_status():
    return {"configured": bool(PORTAL_URL and PORTAL_USERNAME and PORTAL_PASSWORD), "portalUrl": PORTAL_URL, "username": PORTAL_USERNAME, "folder": PORTAL_FOLDER, "verifySsl": VERIFY_SSL}


def _context():
    if VERIFY_SSL:
        return None
    import ssl
    return ssl._create_unverified_context()


def _request_json(url, fields=None, file_path=None, timeout=300):
    fields = fields or {}
    if file_path:
        boundary = "----GISAgent" + uuid.uuid4().hex
        chunks = []
        for name, value in fields.items():
            chunks.extend([f"--{boundary}\r\n".encode(), f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(), str(value).encode("utf-8"), b"\r\n"])
        path = Path(file_path)
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        chunks.extend([f"--{boundary}\r\n".encode(), f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'.encode("utf-8"), f"Content-Type: {content_type}\r\n\r\n".encode(), path.read_bytes(), b"\r\n", f"--{boundary}--\r\n".encode()])
        data = b"".join(chunks)
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    elif fields:
        data = urllib.parse.urlencode(fields).encode("utf-8")
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
    else:
        data = None
        headers = {}
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=_context()) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GeoScene request failed ({exc.code}): {body}") from exc
    if payload.get("error"):
        error = payload["error"]
        raise RuntimeError(f"GeoScene error {error.get('code')}: {error.get('message')} {error.get('details', '')}")
    return payload


def _token():
    response = _request_json(f"{PORTAL_URL}/sharing/rest/generateToken", {"username": PORTAL_USERNAME, "password": PORTAL_PASSWORD, "client": "requestip", "expiration": 60, "f": "json"})
    if not response.get("token"):
        raise RuntimeError("GeoScene Portal did not return a token")
    return response["token"]


def _ensure_folder(token):
    user = urllib.parse.quote(PORTAL_USERNAME)
    folders = _request_json(f"{PORTAL_URL}/sharing/rest/content/users/{user}", {"token": token, "f": "json"}).get("folders", [])
    existing = next((folder for folder in folders if folder.get("title") == PORTAL_FOLDER), None)
    if existing:
        return existing.get("id")
    created = _request_json(f"{PORTAL_URL}/sharing/rest/content/users/{user}/createFolder", {"title": PORTAL_FOLDER, "token": token, "f": "json"})
    return created.get("folder", {}).get("id")


def _poll(status_url, token, timeout=600):
    deadline = time.time() + timeout
    while time.time() < deadline:
        separator = "&" if "?" in status_url else "?"
        status = _request_json(
            f"{status_url}{separator}{urllib.parse.urlencode({'token': token, 'f': 'json'})}"
        )
        state = str(status.get("status", "")).lower()
        if state in {"completed", "success"}:
            return
        if state in {"failed", "error"}:
            raise RuntimeError(status.get("statusMessage") or status.get("message") or "Scene service publishing failed")
        time.sleep(3)
    raise TimeoutError("Timed out waiting for GeoScene scene service publication")




def inspect_publication(publication):
    if not publishing_status()["configured"]:
        raise RuntimeError("Set GEOSCENE_PORTAL_USERNAME and GEOSCENE_PORTAL_PASSWORD before starting the GIS service")
    token = _token()
    details = {}
    for key in ("sourceItemId", "serviceItemId"):
        item_id = publication.get(key)
        if not item_id:
            continue
        item = _request_json(
            f"{PORTAL_URL}/sharing/rest/content/items/{item_id}",
            {"token": token, "f": "json"},
        )
        details[key] = {
            "id": item.get("id"),
            "title": item.get("title"),
            "type": item.get("type"),
            "url": item.get("url"),
            "access": item.get("access"),
            "owner": item.get("owner"),
        }
    publication["itemDetails"] = details
    service_url = (details.get("serviceItemId") or {}).get("url")
    if service_url:
        publication["sceneServiceUrl"] = service_url
    return publication

def share_publication(publication):
    if not publishing_status()["configured"]:
        raise RuntimeError("Set GEOSCENE_PORTAL_USERNAME and GEOSCENE_PORTAL_PASSWORD before starting the GIS service")
    token = _token()
    user = urllib.parse.quote(PORTAL_USERNAME)
    shared_ids = []
    for item_id in (publication.get("sourceItemId"), publication.get("serviceItemId")):
        if not item_id:
            continue
        response = _request_json(
            f"{PORTAL_URL}/sharing/rest/content/users/{user}/items/{item_id}/share",
            {"token": token, "f": "json", "everyone": "true", "org": "true"},
        )
        if response.get("notSharedWith"):
            raise RuntimeError(f"GeoScene item {item_id} was not shared: {response}")
        shared_ids.append(item_id)
    publication["sharedWithEveryone"] = bool(shared_ids)
    return publication

def verify_scene_service(scene_service_url, timeout=90):
    if not scene_service_url:
        raise RuntimeError("Scene service URL is empty")
    separator = "&" if "?" in scene_service_url else "?"
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            details = _request_json(
                f"{scene_service_url}{separator}{urllib.parse.urlencode({'f': 'json'})}",
                timeout=min(30, max(1, int(deadline - time.time()))),
            )
            service_name = details.get("name") or details.get("serviceName")
            if service_name or details.get("layers") is not None:
                return {
                    "url": scene_service_url,
                    "serviceName": service_name,
                    "serviceType": details.get("serviceType") or "SceneServer",
                    "verified": True,
                }
            last_error = RuntimeError("SceneServer response did not contain service metadata")
        except Exception as exc:
            last_error = exc
        time.sleep(3)
    raise RuntimeError(f"SceneServer did not become publicly available: {last_error}")


def publish_slpk(slpk_path, job_id, progress_callback=None):
    if not publishing_status()["configured"]:
        raise RuntimeError("Set GEOSCENE_PORTAL_USERNAME and GEOSCENE_PORTAL_PASSWORD before starting the GIS service")
    slpk_path = Path(slpk_path)
    if not slpk_path.is_file():
        raise FileNotFoundError(f"SLPK not found: {slpk_path}")
    _report_progress(
        progress_callback,
        "portal_uploading",
        "running",
        "正在上传 SLPK 到 GeoScene Portal",
        fileName=slpk_path.name,
        fileSize=slpk_path.stat().st_size,
    )
    token = _token()
    folder_id = _ensure_folder(token)
    user = urllib.parse.quote(PORTAL_USERNAME)
    search = _request_json(f"{PORTAL_URL}/sharing/rest/search", {
        "token": token,
        "f": "json",
        "q": f'owner:"{PORTAL_USERNAME}" AND title:"{job_id}" AND type:"Scene Package"',
        "num": 100,
    })
    existing_items = [item for item in search.get("results", []) if item.get("title") == job_id and item.get("type") == "Scene Package"]
    item_id = existing_items[0].get("id") if existing_items else None
    if not item_id:
        folder_segment = f"/{folder_id}" if folder_id else ""
        added = _request_json(f"{PORTAL_URL}/sharing/rest/content/users/{user}{folder_segment}/addItem", {"token": token, "f": "json", "title": job_id, "type": "Scene Package", "tags": "GISAgent,CityEngine,SLPK,planning", "description": f"Automatically published CityEngine result for {job_id}"}, file_path=slpk_path)
        item_id = added.get("id")
        if not item_id:
            raise RuntimeError(f"GeoScene Portal did not return an uploaded item id: {added}")
    _report_progress(
        progress_callback,
        "portal_uploaded",
        "success",
        "SLPK 已保存为 GeoScene Portal 场景包项目",
        sourceItemId=item_id,
    )
    _report_progress(
        progress_callback,
        "scene_publishing",
        "running",
        "正在将场景包发布为 Scene Service",
        sourceItemId=item_id,
    )
    published = _request_json(f"{PORTAL_URL}/sharing/rest/content/users/{user}/publish", {"token": token, "f": "json", "itemID": item_id, "filetype": "scenepackage", "outputType": "sceneService", "publishParameters": json.dumps({"name": _service_name(job_id), "maxRecordCount": 2000})})
    services = published.get("services") or []
    service = services[0] if services else published
    if service.get("success") is False:
        existing_service = _find_existing_service(token, job_id)
        if existing_service:
            service = {
                "success": True,
                "serviceItemId": existing_service["serviceItemId"],
                "serviceUrl": existing_service["sceneServiceUrl"],
            }
        else:
            raise RuntimeError(f"GeoScene scene service publication failed: {service.get('error') or service}")
    if service.get("statusURL"):
        _poll(service["statusURL"], token)
    service_url = service.get("serviceurl") or service.get("serviceUrl")
    service_item_id = service.get("serviceItemId")
    if not service_url and service_item_id:
        service_url = _request_json(f"{PORTAL_URL}/sharing/rest/content/items/{service_item_id}", {"token": token, "f": "json"}).get("url")
    if not service_url:
        raise RuntimeError(f"GeoScene publish response did not contain a scene service URL: {published}")
    _report_progress(
        progress_callback,
        "scene_published",
        "success",
        "Scene Service 发布完成",
        serviceItemId=service_item_id,
        sceneServiceUrl=service_url,
    )
    return share_publication({"status": "completed", "portalUrl": PORTAL_URL, "sourceItemId": item_id, "serviceItemId": service_item_id, "sceneServiceUrl": service_url, "publishedAt": int(time.time())})
