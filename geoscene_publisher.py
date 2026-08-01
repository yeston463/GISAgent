# -*- coding: utf-8 -*-
import json
import mimetypes
import os
import re
import socket
import subprocess
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path


def _load_env_file(path):
    """Load local key=value settings without replacing explicitly set process env."""
    try:
        lines = Path(path).read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        return
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key:
            os.environ.setdefault(key, value)


_load_env_file(Path(__file__).resolve().with_name(".env"))

PORTAL_URL = os.environ.get("GEOSCENE_PORTAL_URL", "https://product.geosceneenterprise.cn/geoscene").rstrip("/")
PORTAL_USERNAME = os.environ.get("GEOSCENE_PORTAL_USERNAME", "")
PORTAL_PASSWORD = os.environ.get("GEOSCENE_PORTAL_PASSWORD", "")
PORTAL_FOLDER = os.environ.get("GEOSCENE_PORTAL_FOLDER", "GISAgent-CityEngine")
VERIFY_SSL = os.environ.get("GEOSCENE_VERIFY_SSL", "false").lower() in {"1", "true", "yes"}


def _positive_int_env(name, default, minimum=1, maximum=None):
    """Read a bounded positive integer without making a bad .env block publishing."""
    try:
        value = int(os.environ.get(name, default))
    except (TypeError, ValueError):
        value = default
    value = max(minimum, value)
    return min(value, maximum) if maximum is not None else value


def _bool_env(name, default=False):
    value = os.environ.get(name)
    if value is None:
        return bool(default)
    return value.strip().lower() in {"1", "true", "yes", "on"}


# Publishing a small SLPK should create a hosted service within a few minutes.
# The previous code could wait 600 s for the publish job and another 600 s for
# the service item, leaving the UI at "正在发布" for up to 20 minutes.
PUBLISH_TIMEOUT_SECONDS = _positive_int_env(
    "GEOSCENE_PUBLISH_TIMEOUT_SECONDS", 300, minimum=60, maximum=1800
)
PUBLISH_REQUEST_TIMEOUT_SECONDS = _positive_int_env(
    "GEOSCENE_PUBLISH_REQUEST_TIMEOUT_SECONDS", 60, minimum=10, maximum=300
)
PUBLISH_POLL_INTERVAL_SECONDS = _positive_int_env(
    "GEOSCENE_PUBLISH_POLL_INTERVAL_SECONDS", 3, minimum=1, maximum=30
)
OBJECT_STORE_VALIDATE_TIMEOUT_SECONDS = _positive_int_env(
    "GEOSCENE_OBJECT_STORE_VALIDATE_TIMEOUT_SECONDS", 180, minimum=30, maximum=1800
)
OBJECT_STORE_VALIDATE_INTERVAL_SECONDS = _positive_int_env(
    "GEOSCENE_OBJECT_STORE_VALIDATE_INTERVAL_SECONDS", 15, minimum=3, maximum=60
)
OBJECT_STORE_QUIET_SECONDS = _positive_int_env(
    "GEOSCENE_OBJECT_STORE_QUIET_SECONDS", 90, minimum=15, maximum=1800
)
OBJECT_STORE_QUIET_TIMEOUT_SECONDS = _positive_int_env(
    "GEOSCENE_OBJECT_STORE_QUIET_TIMEOUT_SECONDS", 360, minimum=30, maximum=3600
)
STALE_PUBLICATION_MIN_AGE_SECONDS = _positive_int_env(
    "GEOSCENE_STALE_PUBLICATION_MIN_AGE_SECONDS", 600, minimum=60, maximum=86400
)
AUTO_CANCEL_STALE_PUBLICATIONS = _bool_env(
    "GEOSCENE_AUTO_CANCEL_STALE_PUBLICATIONS", False
)
DELETE_CANCELLED_PARTIAL_ITEMS = _bool_env(
    "GEOSCENE_DELETE_CANCELLED_PARTIAL_ITEMS", False
)
DATASTORE_DESCRIBE_TOOL = Path(
    os.environ.get(
        "GEOSCENE_DATASTORE_DESCRIBE_TOOL",
        r"C:\Program Files\GeoScene\DataStore\tools\describedatastore.bat",
    )
)
DATASTORE_OZONE_LOG = Path(
    os.environ.get(
        "GEOSCENE_DATASTORE_OZONE_LOG",
        r"C:\geoscenedatastore\logs\PRODUCT.GEOSCENEENTERPRISE.CN\ozone\ozone.log",
    )
)
DATASTORE_OZONE_CONFIG = Path(
    os.environ.get(
        "GEOSCENE_DATASTORE_OZONE_CONFIG",
        r"C:\geoscenedatastore\ozonedata\etc\hadoop\ozone-site.xml",
    )
)
SERVICES_LOG_FILE = Path(
    os.environ.get(
        "GEOSCENE_SERVICES_LOG",
        str(Path(__file__).resolve().with_name("geoscene-services.log")),
    )
)
UPLOAD_TEMP_DIR = Path(
    os.environ.get(
        "GEOSCENE_UPLOAD_TEMP_DIR",
        str(Path(__file__).resolve().with_name("geoscene-upload-temp")),
    )
)
SERVER_ADMIN_BASE_URL = os.environ.get(
    "GEOSCENE_SERVER_ADMIN_URL",
    "https://product.geosceneenterprise.cn:6443/geoscene/admin",
).rstrip("/")
_portal_parts = urllib.parse.urlsplit(PORTAL_URL)
SERVER_REST_BASE_URL = os.environ.get(
    "GEOSCENE_SERVER_REST_URL",
    f"{_portal_parts.scheme}://{_portal_parts.netloc}/server/rest",
).rstrip("/")
PUBLISHING_JOB_BASE_URL = (
    f"{SERVER_REST_BASE_URL}/services/System/PublishingTools/GPServer/"
    "Publish%20Portal%20Service/jobs"
)
SERVER_ADMIN_REST_BASE_URL = os.environ.get(
    "GEOSCENE_SERVER_ADMIN_REST_URL",
    SERVER_REST_BASE_URL.replace("/rest", "/admin"),
).rstrip("/")
ADMIN_PUBLISHING_JOB_BASE_URL = (
    f"{SERVER_ADMIN_REST_BASE_URL}/services/System/PublishingTools.GPServer/jobs"
)
GISAGENT_PUBLICATION_TITLE_PATTERN = re.compile(
    r"^(?:gisagent(?:[_-]|$)|ce[-_]\d{8,})",
    re.IGNORECASE,
)
OZONE_RECOVERY_PATTERN = re.compile(
    r"ServerNotReadyException|AlreadyClosedException|RaftRetryFailureException|Failed to flush|current state is STARTING"
)
OZONE_TIMESTAMP_PATTERN = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}),")


def _service_name(job_id):
    name = re.sub(r"[^A-Za-z0-9_]", "_", str(job_id)).strip("_")
    if not name:
        name = "cityengine_result"
    if name[0].isdigit():
        name = f"cityengine_{name}"
    return name[:120]


def _tail_text(path, max_bytes=2_000_000):
    try:
        with Path(path).open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            size = stream.tell()
            stream.seek(max(0, size - max_bytes))
            data = stream.read()
    except OSError:
        return ""
    return data.decode("utf-8", errors="replace")


def _describe_datastore():
    if not DATASTORE_DESCRIBE_TOOL.is_file():
        raise FileNotFoundError(f"Data Store describe tool not found: {DATASTORE_DESCRIBE_TOOL}")

    completed = subprocess.run(
        [str(DATASTORE_DESCRIBE_TOOL)],
        capture_output=True,
        text=True,
        encoding="mbcs",
        errors="replace",
        timeout=60,
        check=False,
    )
    output = "\n".join(part for part in (completed.stdout, completed.stderr) if part)
    if not output.strip():
        raise RuntimeError(f"describedatastore exited with code {completed.returncode} and produced no output.")
    return output


def _diagnose_ozone_web_config():
    if not DATASTORE_OZONE_CONFIG.is_file():
        return None

    try:
        root = ET.fromstring(DATASTORE_OZONE_CONFIG.read_text(encoding="utf-8"))
    except Exception as exc:
        raise RuntimeError(f"Could not read Ozone config {DATASTORE_OZONE_CONFIG}: {exc}") from exc

    props = {}
    for prop in root.findall("./property"):
        name = (prop.findtext("name") or "").strip()
        if not name:
            continue
        props[name] = (prop.findtext("value") or "").strip()

    policy = props.get("ozone.http.policy", "")
    issues = []
    if policy == "HTTPS_ONLY":
        for flag_name in (
            "hdds.datanode.http.enabled",
            "ozone.scm.http.enabled",
            "ozone.om.http.enabled",
        ):
            flag_value = props.get(flag_name, "")
            if flag_value.lower() != "true":
                issues.append(f"{flag_name}={flag_value or '<missing>'}")

    return {
        "path": str(DATASTORE_OZONE_CONFIG),
        "policy": policy,
        "issues": issues,
        "isValid": not issues,
    }


def assert_ozone_web_config():
    diagnosis = _diagnose_ozone_web_config()
    if not diagnosis or diagnosis.get("isValid"):
        return
    issue_summary = ", ".join(diagnosis["issues"])
    raise RuntimeError(
        "GeoScene Data Store Ozone config is inconsistent: "
        f"ozone.http.policy={diagnosis.get('policy') or '<missing>'} but {issue_summary}. "
        f"Fix {diagnosis['path']} and set the affected *.http.enabled flags to true before retrying."
    )


def _object_store_descriptor_from_log():
    try:
        text = SERVICES_LOG_FILE.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None

    healthy_matches = list(re.finditer(
        r"Object store validate healthy:\s*id=(?P<id>[A-Za-z0-9_]+),\s*machine=(?P<machine>[A-Za-z0-9_.-]+)",
        text,
        re.IGNORECASE,
    ))
    if healthy_matches:
        match = healthy_matches[-1]
        return {"id": match.group("id"), "machine": match.group("machine")}

    store_matches = re.findall(
        r"(?im)Information for object store\s+(?P<id>[A-Za-z0-9_]+)\b",
        text,
    )
    machine_matches = re.findall(
        r"(?im)Registered machines\.+\s+(?P<machine>[A-Za-z0-9_.-]+)\b",
        text,
    )
    if store_matches:
        return {
            "id": store_matches[-1],
            "machine": machine_matches[-1] if machine_matches else socket.getfqdn().upper(),
        }
    return None


def _object_store_descriptor():
    object_store_id = os.environ.get("GEOSCENE_OBJECT_STORE_ID")
    object_store_machine = os.environ.get("GEOSCENE_OBJECT_STORE_MACHINE")
    if object_store_id and object_store_machine:
        return {"id": object_store_id, "machine": object_store_machine}

    descriptor = _object_store_descriptor_from_log()
    if descriptor:
        return {
            "id": object_store_id or descriptor["id"],
            "machine": object_store_machine or descriptor["machine"],
        }

    description = _describe_datastore()
    object_store_match = re.search(
        r"(?im)^\s*(?:Information for )?object store\s+(?P<id>[A-Za-z0-9_]+)\b",
        description,
    )
    machine_matches = re.findall(
        r"(?im)^\s*Registered machines\.+\s+(?P<machine>[A-Za-z0-9_.-]+)\b",
        description,
    )
    if not object_store_match:
        raise RuntimeError("describedatastore did not expose object-store id.")
    return {
        "id": object_store_match.group("id"),
        "machine": object_store_machine or (machine_matches[-1] if machine_matches else socket.getfqdn().upper()),
    }


def _validate_object_store(token):
    descriptor = _object_store_descriptor()
    validate_url = (
        f"{SERVER_ADMIN_BASE_URL}/data/items/cloudStores/"
        f"AGSDataStore_objectstore_{descriptor['id']}/machines/{descriptor['machine']}/validate"
    )
    validation = _request_json(
        validate_url,
        {
            "token": token,
            "f": "json",
        },
        timeout=45,
    )
    return descriptor, validation


def wait_object_store_healthy(token, timeout=OBJECT_STORE_VALIDATE_TIMEOUT_SECONDS, poll_interval=OBJECT_STORE_VALIDATE_INTERVAL_SECONDS):
    assert_ozone_web_config()
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            descriptor, validation = _validate_object_store(token)
            machine = (validation.get("machines") or [{}])[0]
            healthy = (
                validation.get("status") == "success"
                and validation.get("datastore.overallhealth") == "Healthy"
                and machine.get("machine.overallhealth") == "Healthy"
                and machine.get("status") == "Started"
                and machine.get("isSCMHealthy") is True
                and machine.get("isOMHealthy") is True
                and machine.get("isDataNodeHealthy") is True
                and ((machine.get("s3gStatus") or {}).get("isS3GHealthy") is True)
            )
            if healthy:
                return {
                    "descriptor": descriptor,
                    "validation": validation,
                    "machine": machine,
                }
            last_error = RuntimeError(
                f"status={validation.get('status')}, overallhealth={validation.get('datastore.overallhealth')}"
            )
        except Exception as exc:
            last_error = exc
        sleep_for = min(poll_interval, max(0, deadline - time.monotonic()))
        if sleep_for > 0:
            time.sleep(sleep_for)
    raise TimeoutError(
        f"Timed out waiting for GeoScene Data Store object store to become Healthy: {last_error}"
    )


def _latest_ozone_recovery_time():
    text = _tail_text(DATASTORE_OZONE_LOG)
    if not text.strip():
        return None

    current_timestamp = None
    last_error_timestamp = None
    for line in text.splitlines():
        match = OZONE_TIMESTAMP_PATTERN.match(line)
        if match:
            try:
                current_timestamp = datetime.strptime(match.group("ts"), "%Y-%m-%d %H:%M:%S")
            except ValueError:
                current_timestamp = None
        if current_timestamp and OZONE_RECOVERY_PATTERN.search(line):
            last_error_timestamp = current_timestamp
    return last_error_timestamp


def wait_object_store_quiet(timeout=OBJECT_STORE_QUIET_TIMEOUT_SECONDS, quiet_seconds=OBJECT_STORE_QUIET_SECONDS, poll_interval=5):
    deadline = time.monotonic() + timeout
    last_error = _latest_ozone_recovery_time()
    if last_error is None:
        return {
            "quietForSeconds": None,
            "lastErrorTime": None,
        }

    while True:
        now = datetime.now()
        quiet_for_seconds = max(0, int((now - last_error).total_seconds()))
        if quiet_for_seconds >= quiet_seconds:
            return {
                "quietForSeconds": quiet_for_seconds,
                "lastErrorTime": last_error.isoformat(sep=" "),
            }
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(
                "GeoScene Data Store object store is still recovering: "
                f"latest Ozone/Ratis retry was at {last_error.strftime('%Y-%m-%d %H:%M:%S')} "
                f"and the required quiet window is {quiet_seconds} seconds."
            )
        time.sleep(min(poll_interval, remaining))
        refreshed = _latest_ozone_recovery_time()
        if refreshed is not None:
            last_error = refreshed


def _portal_item_status(token, item_id):
    user = urllib.parse.quote(PORTAL_USERNAME)
    return _request_json(
        f"{PORTAL_URL}/sharing/rest/content/users/{user}/items/{item_id}/status",
        {"token": token, "f": "json"},
        timeout=30,
    )


def _publishing_job_url(job_id):
    return f"{PUBLISHING_JOB_BASE_URL}/{urllib.parse.quote(str(job_id), safe='')}"


def _admin_publishing_job_url(job_id):
    return f"{ADMIN_PUBLISHING_JOB_BASE_URL}/{urllib.parse.quote(str(job_id), safe='')}"


def _delete_publish_job(job_id, token):
    try:
        response = _request_json(
            f"{_admin_publishing_job_url(job_id)}/delete",
            {"token": token, "f": "json"},
            timeout=60,
        )
        return response.get("status") == "success"
    except Exception:
        return False


def discover_stale_publications(
    token,
    min_age_seconds=STALE_PUBLICATION_MIN_AGE_SECONDS,
    now_ms=None,
):
    """Find GISAgent-owned publish jobs that are old, partial, and have no SceneServer."""
    now_ms = int(time.time() * 1000) if now_ms is None else int(now_ms)
    cutoff_ms = now_ms - int(min_age_seconds * 1000)
    search = _request_json(
        f"{PORTAL_URL}/sharing/rest/search",
        {
            "token": token,
            "f": "json",
            "q": f'owner:"{PORTAL_USERNAME}" AND type:"Scene Service"',
            "num": 100,
            "sortField": "modified",
            "sortOrder": "desc",
        },
        timeout=45,
    )

    stale = []
    for item in search.get("results", []):
        item_id = item.get("id")
        title = str(item.get("title") or "")
        modified = int(item.get("modified") or item.get("created") or 0)
        if (
            not item_id
            or item.get("type") != "Scene Service"
            or not GISAGENT_PUBLICATION_TITLE_PATTERN.match(title)
            or modified <= 0
            or modified > cutoff_ms
        ):
            continue

        try:
            status = _portal_item_status(token, item_id)
        except Exception:
            continue
        if str(status.get("status") or "").lower() != "partial":
            continue

        job_info = status.get("jobInfo") or {}
        job_id = job_info.get("jobId")
        if not job_id:
            continue

        # A valid SceneServer should never be cancelled just because Portal
        # left stale status metadata behind.
        service_url = item.get("url")
        if service_url:
            try:
                _read_scene_service(service_url, token=token, timeout=15)
                continue
            except Exception:
                pass

        job_url = _publishing_job_url(job_id)
        try:
            job = _request_json(
                job_url,
                {"token": token, "f": "json"},
                timeout=30,
            )
        except Exception:
            continue
        job_status = str(job.get("jobStatus") or "")
        if job_status not in {
            "esriJobSubmitted",
            "esriJobWaiting",
            "esriJobExecuting",
        }:
            continue

        stale.append(
            {
                "itemId": item_id,
                "title": title,
                "serviceUrl": service_url,
                "modified": modified,
                "ageSeconds": max(0, int((now_ms - modified) / 1000)),
                "portalStatus": status.get("status"),
                "jobId": job_id,
                "jobStatus": job_status,
                "jobUrl": job_url,
                "adminJobUrl": _admin_publishing_job_url(job_id),
            }
        )
    return stale


def _wait_publish_job_terminal(job_url, token, timeout=45, poll_interval=2):
    deadline = time.monotonic() + timeout
    last = {}
    while time.monotonic() < deadline:
        last = _request_json(
            job_url,
            {"token": token, "f": "json"},
            timeout=min(30, max(1, int(deadline - time.monotonic()))),
        )
        state = str(last.get("jobStatus") or "")
        if state not in {
            "esriJobSubmitted",
            "esriJobWaiting",
            "esriJobExecuting",
            "esriJobCancelling",
        }:
            return last
        time.sleep(min(poll_interval, max(0, deadline - time.monotonic())))
    raise TimeoutError(
        f"Timed out waiting for GeoScene publishing job to stop: "
        f"{last.get('jobId') or job_url} ({last.get('jobStatus') or 'unknown'})"
    )


def cancel_stale_publications(
    token,
    publications,
    delete_partial_items=False,
    wait_timeout=45,
):
    """Cancel only pre-screened stale jobs and optionally remove their partial items."""
    user = urllib.parse.quote(PORTAL_USERNAME)
    results = []
    for publication in publications:
        job_url = publication.get("jobUrl") or _publishing_job_url(publication["jobId"])
        item_id = publication["itemId"]
        cancelled = _cancel_publish_job(f"{job_url}/status", token)
        terminal = None
        admin_deleted = False
        deleted = False
        error = None
        try:
            if cancelled:
                try:
                    terminal = _wait_publish_job_terminal(
                        job_url,
                        token,
                        timeout=wait_timeout,
                    )
                except TimeoutError:
                    admin_deleted = _delete_publish_job(publication["jobId"], token)
                    if not admin_deleted:
                        raise
            if delete_partial_items and cancelled:
                deletion = _request_json(
                    f"{PORTAL_URL}/sharing/rest/content/users/{user}/items/{item_id}/delete",
                    {"token": token, "f": "json"},
                    timeout=30,
                )
                deleted = deletion.get("success") is True
        except Exception as exc:
            error = str(exc)
        results.append(
            {
                **publication,
                "cancelRequested": cancelled,
                "adminJobDeleted": admin_deleted,
                "terminalJobStatus": (terminal or {}).get("jobStatus"),
                "itemDeleted": deleted,
                "error": error,
            }
        )
    return results


def _find_service_items(token, job_id):
    search = _request_json(f"{PORTAL_URL}/sharing/rest/search", {
        "token": token,
        "f": "json",
        "q": f'owner:"{PORTAL_USERNAME}" AND title:"{job_id}" AND type:"Scene Service"',
        "num": 100,
    })
    return [
        result
        for result in search.get("results", [])
        if result.get("title") == job_id and result.get("type") == "Scene Service"
    ]


def _read_scene_service(scene_service_url, token=None, timeout=30):
    fields = {"f": "json"}
    if token:
        fields["token"] = token
    details = _request_json(scene_service_url, fields, timeout=timeout)
    service_name = details.get("name") or details.get("serviceName")
    if not service_name and details.get("layers") is None:
        raise RuntimeError("SceneServer response did not contain service metadata")
    return {
        "url": scene_service_url,
        "serviceName": service_name,
        "serviceType": details.get("serviceType") or "SceneServer",
        "verified": True,
    }


def _find_existing_service(token, job_id, items=None):
    for item in items if items is not None else _find_service_items(token, job_id):
        service_url = item.get("url")
        if not service_url:
            details = _request_json(
                f"{PORTAL_URL}/sharing/rest/content/items/{item['id']}",
                {"token": token, "f": "json"},
            )
            service_url = details.get("url")
        if not service_url:
            continue
        try:
            hosted_service = _read_scene_service(service_url, token=token)
        except Exception:
            # A Portal item can remain after its hosting job failed. It must not
            # be reused unless the corresponding SceneServer really exists.
            continue
        return {
            "serviceItemId": item.get("id"),
            "sceneServiceUrl": service_url,
            "hostedService": hosted_service,
        }
    return None


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


def _poll(status_url, token, timeout=600, poll_interval=3):
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
        time.sleep(poll_interval)
    raise TimeoutError("Timed out waiting for GeoScene scene service publication")


def _poll_item_status(token, item_id, timeout=600, poll_interval=3):
    user = urllib.parse.quote(PORTAL_USERNAME)
    status_url = f"{PORTAL_URL}/sharing/rest/content/users/{user}/items/{item_id}/status"
    deadline = time.time() + timeout
    last_status = {}
    while time.time() < deadline:
        last_status = _request_json(status_url, {"token": token, "f": "json"})
        state = str(last_status.get("status", "")).lower()
        if state in {"completed", "success"}:
            return last_status
        if state in {"failed", "error"}:
            raise RuntimeError(
                last_status.get("statusMessage")
                or last_status.get("message")
                or "Scene service publishing failed"
            )
        # GeoScene reports "partial" while the asynchronous hosting job is
        # still being assembled. Only a real SceneServer response is success.
        time.sleep(poll_interval)
    state = last_status.get("status") or "unknown"
    message = last_status.get("statusMessage") or last_status.get("message") or ""
    suffix = f": {message}" if message else ""
    raise TimeoutError(f"Timed out waiting for Portal item {item_id} (status={state}){suffix}")


def _cancel_publish_job(status_url, token):
    if not status_url:
        return False
    job_url = status_url.rstrip("/")
    if job_url.endswith("/status"):
        job_url = job_url[: -len("/status")]
    for suffix in ("/cancelJob", "/cancel"):
        try:
            _request_json(f"{job_url}{suffix}", {"token": token, "f": "json"}, timeout=30)
            return True
        except Exception:
            continue
    return False




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

def verify_scene_service(scene_service_url, timeout=90, token=None, poll_interval=3):
    if not scene_service_url:
        raise RuntimeError("Scene service URL is empty")
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            return _read_scene_service(
                scene_service_url,
                token=token,
                timeout=min(30, max(1, int(deadline - time.time()))),
            )
        except Exception as exc:
            last_error = exc
        time.sleep(poll_interval)
    raise RuntimeError(f"SceneServer did not become publicly available: {last_error}")


def publish_slpk(slpk_path, job_id, progress_callback=None):
    if not publishing_status()["configured"]:
        raise RuntimeError("Set GEOSCENE_PORTAL_USERNAME and GEOSCENE_PORTAL_PASSWORD before starting the GIS service")
    slpk_path = Path(slpk_path)
    if not slpk_path.is_file():
        raise FileNotFoundError(f"SLPK not found: {slpk_path}")

    publish_deadline = time.monotonic() + PUBLISH_TIMEOUT_SECONDS

    def remaining(stage):
        seconds = int(publish_deadline - time.monotonic())
        if seconds <= 0:
            raise TimeoutError(
                f"GeoScene 发布超过 {PUBLISH_TIMEOUT_SECONDS} 秒，停止等待（阶段：{stage}）。"
                "请检查 Data Store 与托管 Server 健康状态后重新发布。"
            )
        return seconds

    def request_timeout(stage):
        return min(PUBLISH_REQUEST_TIMEOUT_SECONDS, remaining(stage))

    token = _token()
    user = urllib.parse.quote(PORTAL_USERNAME)
    stale_publications = discover_stale_publications(token)
    if stale_publications:
        stale_job_ids = [item["jobId"] for item in stale_publications]
        _report_progress(
            progress_callback,
            "stale_publications_detected",
            "running",
            f"检测到 {len(stale_publications)} 个长期未完成的 GeoScene 发布任务",
            jobIds=stale_job_ids,
            itemIds=[item["itemId"] for item in stale_publications],
        )
        if not AUTO_CANCEL_STALE_PUBLICATIONS:
            raise RuntimeError(
                f"检测到 {len(stale_publications)} 个 GISAgent 僵尸发布任务仍在运行："
                f"{', '.join(stale_job_ids)}。"
                "它们会持续占用 GeoScene Data Store 写入通道。"
                "请先运行 scripts/cleanup_geoscene_stale_publications.py --execute "
                "--delete-partial-items，或显式设置 "
                "GEOSCENE_AUTO_CANCEL_STALE_PUBLICATIONS=true 后重试。"
            )
        cleanup_results = cancel_stale_publications(
            token,
            stale_publications,
            delete_partial_items=DELETE_CANCELLED_PARTIAL_ITEMS,
            wait_timeout=min(45, remaining("清理僵尸发布任务")),
        )
        cleanup_failures = [
            result
            for result in cleanup_results
            if not result.get("cancelRequested") or result.get("error")
        ]
        if cleanup_failures:
            raise RuntimeError(
                "GeoScene 僵尸发布任务清理不完整："
                + "; ".join(
                    f"{result.get('jobId')}: "
                    f"{result.get('error') or 'cancel request was rejected'}"
                    for result in cleanup_failures
                )
            )
        _report_progress(
            progress_callback,
            "stale_publications_cancelled",
            "success",
            f"已停止 {len(cleanup_results)} 个长期未完成的 GeoScene 发布任务",
            jobIds=stale_job_ids,
            deletedItemIds=[
                result["itemId"]
                for result in cleanup_results
                if result.get("itemDeleted")
            ],
        )

    service_items = _find_service_items(token, job_id)
    existing_service = _find_existing_service(token, job_id, service_items)
    search = _request_json(f"{PORTAL_URL}/sharing/rest/search", {
        "token": token,
        "f": "json",
        "q": f'owner:"{PORTAL_USERNAME}" AND title:"{job_id}" AND type:"Scene Package"',
        "num": 100,
    }, timeout=request_timeout("检查已有场景包"))
    existing_items = sorted(
        [item for item in search.get("results", []) if item.get("title") == job_id and item.get("type") == "Scene Package"],
        key=lambda item: item.get("modified") or item.get("created") or 0,
        reverse=True,
    )
    # A failed/partial Scene Service can leave its source Scene Package bound
    # to an internal Portal publishing job. Reusing that package repeatedly
    # creates new service items which remain in `partial`. Upload a fresh source
    # package whenever orphan service items exist.
    force_fresh_source = bool(service_items) and existing_service is None
    item_id = None if force_fresh_source else (existing_items[0].get("id") if existing_items else None)
    if existing_service:
        _report_progress(
            progress_callback,
            "scene_published",
            "success",
            "已复用验证通过的 Scene Service",
            serviceItemId=existing_service["serviceItemId"],
            sceneServiceUrl=existing_service["sceneServiceUrl"],
        )
        return share_publication({
            "status": "completed",
            "portalUrl": PORTAL_URL,
            "sourceItemId": item_id,
            "serviceItemId": existing_service["serviceItemId"],
            "sceneServiceUrl": existing_service["sceneServiceUrl"],
            "hostedService": existing_service["hostedService"],
            "publishedAt": int(time.time()),
        })

    _report_progress(
        progress_callback,
        "object_store_checking",
        "running",
        "正在检查 GeoScene Data Store 对象存储健康状态",
    )
    object_store = wait_object_store_healthy(
        token,
        timeout=min(OBJECT_STORE_VALIDATE_TIMEOUT_SECONDS, remaining("检查对象存储健康状态")),
        poll_interval=OBJECT_STORE_VALIDATE_INTERVAL_SECONDS,
    )
    descriptor = object_store.get("descriptor") or {}
    machine = object_store.get("machine") or {}
    _report_progress(
        progress_callback,
        "object_store_checking",
        "success",
        "GeoScene Data Store 对象存储健康",
        objectStoreId=descriptor.get("id"),
        objectStoreMachine=descriptor.get("machine") or machine.get("name"),
        overallHealth=(object_store.get("validation") or {}).get("datastore.overallhealth"),
    )
    _report_progress(
        progress_callback,
        "object_store_stabilizing",
        "running",
        "正在等待 GeoScene Data Store 对象存储完成恢复并进入稳定窗口",
    )
    object_store_quiet = wait_object_store_quiet(
        timeout=min(OBJECT_STORE_QUIET_TIMEOUT_SECONDS, remaining("等待对象存储稳定")),
        quiet_seconds=OBJECT_STORE_QUIET_SECONDS,
        poll_interval=min(5, PUBLISH_POLL_INTERVAL_SECONDS or 5),
    )
    _report_progress(
        progress_callback,
        "object_store_stabilizing",
        "success",
        "GeoScene Data Store 对象存储已通过稳定窗口检查",
        quietForSeconds=object_store_quiet.get("quietForSeconds"),
        lastRecoveryEventAt=object_store_quiet.get("lastErrorTime"),
    )

    if not item_id:
        folder_id = _ensure_folder(token)
        folder_segment = f"/{folder_id}" if folder_id else ""
        upload_path = slpk_path
        temporary_upload = None
        _report_progress(
            progress_callback,
            "portal_uploading",
            "running",
            "正在上传 SLPK 到 GeoScene Portal",
            fileName=slpk_path.name,
            fileSize=slpk_path.stat().st_size,
        )
        if force_fresh_source:
            UPLOAD_TEMP_DIR.mkdir(parents=True, exist_ok=True)
            temporary_upload = UPLOAD_TEMP_DIR / (
                f"{slpk_path.stem}-retry-{uuid.uuid4().hex[:8]}{slpk_path.suffix}"
            )
            shutil.copyfile(slpk_path, temporary_upload)
            upload_path = temporary_upload
        try:
            added = _request_json(f"{PORTAL_URL}/sharing/rest/content/users/{user}{folder_segment}/addItem", {"token": token, "f": "json", "title": job_id, "type": "Scene Package", "tags": "GISAgent,CityEngine,SLPK,planning", "description": f"Automatically published CityEngine result for {job_id}"}, file_path=upload_path, timeout=request_timeout("上传 SLPK"))
        finally:
            if temporary_upload:
                temporary_upload.unlink(missing_ok=True)
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

    service_name = _service_name(job_id)
    if service_items:
        # Preserve orphaned Portal items for manual inspection, but publish a
        # fresh service name so retries are not trapped by the stale item.
        service_name = _service_name(f"{job_id}_retry_{uuid.uuid4().hex[:8]}")
    status_url = None
    try:
        published = _request_json(f"{PORTAL_URL}/sharing/rest/content/users/{user}/publish", {"token": token, "f": "json", "itemID": item_id, "filetype": "scenepackage", "outputType": "sceneService", "publishParameters": json.dumps({"name": service_name, "maxRecordCount": 2000})}, timeout=request_timeout("创建 Scene Service"))
        services = published.get("services") or []
        service = services[0] if services else published
        if service.get("success") is False:
            existing_service = _find_existing_service(token, job_id, service_items)
            if existing_service:
                service = {
                    "success": True,
                    "serviceItemId": existing_service["serviceItemId"],
                    "serviceUrl": existing_service["sceneServiceUrl"],
                }
            else:
                raise RuntimeError(f"GeoScene scene service publication failed: {service.get('error') or service}")
        status_url = service.get("statusURL")
        if status_url:
            _poll(
                status_url,
                token,
                timeout=remaining("等待发布任务"),
                poll_interval=PUBLISH_POLL_INTERVAL_SECONDS,
            )
        service_item_id = service.get("serviceItemId")
        if not service_item_id:
            raise RuntimeError(f"GeoScene publish response did not contain a service item id: {published}")
        try:
            _poll_item_status(
                token,
                service_item_id,
                timeout=remaining("等待 Scene Service 就绪"),
                poll_interval=PUBLISH_POLL_INTERVAL_SECONDS,
            )
        except Exception as exc:
            raise RuntimeError(
                f"Portal 项目已创建，但 SceneServer 服务未生成（服务项目 {service_item_id}）：{exc}"
            ) from exc
        service_url = service.get("serviceurl") or service.get("serviceUrl")
        if not service_url and service_item_id:
            service_url = _request_json(f"{PORTAL_URL}/sharing/rest/content/items/{service_item_id}", {"token": token, "f": "json"}).get("url")
        if not service_url:
            raise RuntimeError(f"GeoScene publish response did not contain a scene service URL: {published}")
        try:
            hosted_service = verify_scene_service(
                service_url,
                token=token,
                timeout=remaining("验证 SceneServer"),
                poll_interval=PUBLISH_POLL_INTERVAL_SECONDS,
            )
        except Exception as exc:
            raise RuntimeError(
                f"Portal 项目已创建，但 SceneServer 服务未生成（服务项目 {service_item_id}）：{exc}"
            ) from exc
    except Exception:
        _cancel_publish_job(status_url, token)
        raise
    _report_progress(
        progress_callback,
        "scene_published",
        "success",
        "Scene Service 发布完成",
        serviceItemId=service_item_id,
        sceneServiceUrl=service_url,
    )
    return share_publication({"status": "completed", "portalUrl": PORTAL_URL, "sourceItemId": item_id, "serviceItemId": service_item_id, "sceneServiceUrl": service_url, "hostedService": hosted_service, "publishedAt": int(time.time())})
