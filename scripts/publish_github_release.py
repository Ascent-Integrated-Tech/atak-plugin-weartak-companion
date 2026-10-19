#!/usr/bin/env python3
"""Publish the latest canonical signed CIV APK as a rolling GitHub release."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Any


EXPECTED_PROJECT = "aegorsuch/atak-plugin-weartak-companion"
EXPECTED_SERVER = "git.tak.gov"
EXPECTED_BRANCH = "develop"
EXPECTED_GITHUB_REPOSITORY = "Ascent-Integrated-Tech/WearTAK-ATAK-Plugin"
RELEASE_TAG = "tak-gov-ci-cd-pipeline"
RELEASE_TITLE = "TAK.gov CI/CD Pipeline Release"
ASSET_PREFIX = "WearTAK-ATAK-Plugin-civ-release-pipeline-"
RELEASE_OWNERSHIP_MARKER = "<!-- weartak-companion-rolling-release:managed -->"
ASSET_LABEL_PREFIX = "WearTAK Companion managed APK sha256="
EXPECTED_SIGNER_SHA256 = "f24a38057275fcecf67be975ab803d12f75dc23581bef69cba9eb03a15bb8c17"


class PublishError(RuntimeError):
    pass


class ApiClient:
    def __init__(self, base_url: str, token: str, token_header: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.token_header = token_header

    def request(
        self,
        method: str,
        url: str,
        *,
        json_body: dict[str, Any] | None = None,
        data: bytes | None = None,
        accept: str = "application/vnd.github+json",
        content_type: str | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        token_value = (
            f"Bearer {self.token}"
            if self.token_header == "Authorization"
            else self.token
        )
        headers = {
            "Accept": accept,
            "User-Agent": "WearTAK-CI-release-publisher",
            self.token_header: token_value,
        }
        if json_body is not None:
            data = json.dumps(json_body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif content_type is not None:
            headers["Content-Type"] = content_type

        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return response.status, dict(response.headers.items()), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers.items()), error.read()
        except urllib.error.URLError as error:
            raise PublishError(f"Request failed for {urllib.parse.urlsplit(url).path}") from error

    def json_request(
        self, method: str, path: str, *, body: dict[str, Any] | None = None
    ) -> tuple[int, Any]:
        status, _, response_body = self.request(
            method, f"{self.base_url}{path}", json_body=body
        )
        if not response_body:
            return status, None
        try:
            return status, json.loads(response_body)
        except json.JSONDecodeError as error:
            raise PublishError(f"Invalid JSON response from {path}") from error


class GitHubClient(ApiClient):
    def __init__(self, repository: str, token: str) -> None:
        super().__init__("https://api.github.com", token, "Authorization")
        self.repository = repository
        self.repository_path = "/repos/" + repository

    def get_release(self) -> dict[str, Any] | None:
        path = f"{self.repository_path}/releases/tags/{urllib.parse.quote(RELEASE_TAG)}"
        status, result = self.json_request("GET", path)
        if status == 404:
            return None
        if status != 200:
            raise PublishError(f"Could not inspect the rolling GitHub release (HTTP {status})")
        return result

    def require_existing_tag(self) -> None:
        path = f"{self.repository_path}/git/ref/tags/{urllib.parse.quote(RELEASE_TAG)}"
        status, _ = self.json_request("GET", path)
        if status == 404:
            raise PublishError(
                f"Required pre-created GitHub tag {RELEASE_TAG!r} is missing; "
                "the publisher will not create or move tags"
            )
        if status != 200:
            raise PublishError(f"Could not inspect the fixed GitHub tag (HTTP {status})")

    def create_release(self) -> dict[str, Any]:
        status, result = self.json_request(
            "POST",
            f"{self.repository_path}/releases",
            body={
                "tag_name": RELEASE_TAG,
                "name": RELEASE_TITLE,
                "body": "No signed distributable has been published yet.",
                "draft": False,
                "prerelease": False,
            },
        )
        if status != 201:
            raise PublishError(f"Could not create the rolling GitHub release (HTTP {status})")
        return result

    def list_assets(self, release_id: int) -> list[dict[str, Any]]:
        assets: list[dict[str, Any]] = []
        page = 1
        while True:
            status, page_assets = self.json_request(
                "GET",
                f"{self.repository_path}/releases/{release_id}/assets?per_page=100&page={page}",
            )
            if status != 200 or not isinstance(page_assets, list):
                raise PublishError(f"Could not list rolling release assets (HTTP {status})")
            assets.extend(page_assets)
            if len(page_assets) < 100:
                return assets
            page += 1

    def upload_asset(
        self, release: dict[str, Any], name: str, label: str, content: bytes
    ) -> None:
        upload_url = release["upload_url"].split("{", 1)[0]
        parsed_url = urllib.parse.urlsplit(upload_url)
        if parsed_url.scheme != "https" or parsed_url.hostname != "uploads.github.com":
            raise PublishError("GitHub returned an unexpected release asset upload URL")
        query = urllib.parse.urlencode({"name": name, "label": label})
        url = f"{upload_url}?{query}"
        status, _, _ = self.request(
            "POST",
            url,
            data=content,
            accept="application/vnd.github+json",
            content_type="application/vnd.android.package-archive",
        )
        if status != 201:
            raise PublishError(f"Could not upload signed APK asset (HTTP {status})")

    def download_asset(self, asset_id: int) -> bytes:
        status, _, content = self.request(
            "GET",
            f"https://api.github.com{self.repository_path}/releases/assets/{asset_id}",
            accept="application/octet-stream",
        )
        if status != 200:
            raise PublishError(f"Could not verify uploaded APK asset (HTTP {status})")
        return content

    def update_release_body(self, release_id: int, body: str) -> None:
        status, _ = self.json_request(
            "PATCH",
            f"{self.repository_path}/releases/{release_id}",
            body={"body": body},
        )
        if status != 200:
            raise PublishError(f"Could not update rolling release metadata (HTTP {status})")

    def delete_asset(self, asset_id: int) -> None:
        status, _ = self.json_request(
            "DELETE", f"{self.repository_path}/releases/assets/{asset_id}"
        )
        if status != 204:
            raise PublishError(f"Could not remove a superseded rolling APK (HTTP {status})")

    def dispatch_release_test(self, release_id: int, pipeline_id: str, body: str) -> None:
        status, _ = self.json_request(
            "POST",
            f"{self.repository_path}/dispatches",
            body={
                "event_type": "weartak-rolling-release-published",
                "client_payload": {
                    "pipeline_id": pipeline_id,
                    "release": {
                        "id": release_id,
                        "tag_name": RELEASE_TAG,
                        "draft": False,
                        "html_url": f"https://github.com/{self.repository}/releases/tag/{RELEASE_TAG}",
                        "body": body,
                    },
                },
            },
        )
        if status != 204:
            raise PublishError(f"Could not notify the release tester workflow (HTTP {status})")


class GitLabClient(ApiClient):
    def __init__(self, api_url: str, project_id: str, job_token: str) -> None:
        super().__init__(api_url, job_token, "JOB-TOKEN")
        self.project_path = f"/projects/{urllib.parse.quote(project_id, safe='')}"

    def develop_branch(self) -> dict[str, Any]:
        query = urllib.parse.urlencode({"search": EXPECTED_BRANCH, "per_page": "100"})
        status, branches = self.json_request(
            "GET", f"{self.project_path}/repository/branches?{query}"
        )
        if status != 200 or not isinstance(branches, list):
            raise PublishError(
                f"Could not identify canonical develop branch tip (HTTP {status})"
            )
        branch = next(
            (item for item in branches if item.get("name") == EXPECTED_BRANCH), None
        )
        if branch is None:
            raise PublishError("Canonical develop branch was not returned by GitLab")
        return branch


def require_environment(env: dict[str, str]) -> dict[str, str]:
    required = (
        "GITHUB_RELEASE_TOKEN",
        "GITHUB_REPOSITORY",
        "CI_SERVER_HOST",
        "CI_PROJECT_PATH",
        "CI_PROJECT_ID",
        "CI_COMMIT_BRANCH",
        "CI_PIPELINE_SOURCE",
        "CI_PIPELINE_ID",
        "CI_COMMIT_SHA",
        "CI_API_V4_URL",
        "CI_JOB_TOKEN",
        "CI_PROJECT_DIR",
    )
    missing = [name for name in required if not env.get(name)]
    if missing:
        raise PublishError("Missing required CI settings: " + ", ".join(missing))

    expected = {
        "CI_SERVER_HOST": EXPECTED_SERVER,
        "CI_PROJECT_PATH": EXPECTED_PROJECT,
        "CI_COMMIT_BRANCH": EXPECTED_BRANCH,
        "CI_PIPELINE_SOURCE": "push",
        "GITHUB_REPOSITORY": EXPECTED_GITHUB_REPOSITORY,
    }
    for name, value in expected.items():
        if env[name] != value:
            raise PublishError(f"Refusing publication from unexpected {name}")
    if env.get("CI_COMMIT_REF_PROTECTED") != "true":
        raise PublishError("Refusing publication from an unprotected branch")
    if not re.fullmatch(r"[0-9]+", env["CI_PIPELINE_ID"]):
        raise PublishError("CI_PIPELINE_ID must be numeric")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", env["CI_COMMIT_SHA"]):
        raise PublishError("CI_COMMIT_SHA must be a full commit SHA")
    return env


def select_signed_apk(project_dir: Path) -> Path:
    candidates = sorted(
        path
        for path in project_dir.glob("*.apk")
        if path.name.endswith("-civ-release.apk")
        and path.name.startswith("ATAK-Plugin-atak-plugin-weartak-companion-")
        and path.is_file()
    )
    if len(candidates) != 1:
        raise PublishError(
            f"Expected exactly one Companion CIV release APK; found {len(candidates)}"
        )
    apk = candidates[0]
    if not zipfile.is_zipfile(apk):
        raise PublishError("The selected release APK is not a valid Android package")
    with zipfile.ZipFile(apk) as archive:
        if "AndroidManifest.xml" not in archive.namelist():
            raise PublishError("The selected release APK has no Android manifest")
    return apk


def read_plugin_version(apk: Path) -> str:
    prefix = "ATAK-Plugin-atak-plugin-weartak-companion-"
    match = re.match(
        re.escape(prefix) + r"([0-9]+(?:\.[0-9]+)+)-", apk.name
    )
    if not match:
        raise PublishError("Could not identify plugin version in the release APK filename")
    return match.group(1)


def find_apksigner() -> str:
    executable = shutil.which("apksigner")
    if executable:
        return executable
    roots = {
        os.environ.get("ANDROID_SDK_ROOT", ""),
        os.environ.get("ANDROID_HOME", ""),
        "/opt/android-sdk-linux",
    }
    candidates = [
        path
        for root in roots
        if root
        for path in Path(root).glob("build-tools/*/apksigner")
        if path.is_file()
    ]
    if not candidates:
        raise PublishError("Android apksigner is not available in the build image")
    version_key = lambda value: tuple(
        int(part) for part in re.findall(r"\d+", value.parent.name)
    )
    return str(max(candidates, key=version_key))


def verify_signer(apk: Path) -> None:
    try:
        result = subprocess.run(
            [find_apksigner(), "verify", "--print-certs", str(apk)],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise PublishError("Android APK signature verification failed") from error
    digests = re.findall(
        r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", result.stdout
    )
    if EXPECTED_SIGNER_SHA256 not in {digest.lower() for digest in digests}:
        raise PublishError("APK signer does not match the TAK Product Center release certificate")


def verify_release_order(release: dict[str, Any], pipeline_id: str) -> None:
    previous_body = str(release.get("body", ""))
    match = re.search(r"(?m)^- Pipeline: \[#([0-9]+)\]", previous_body)
    if match and int(match.group(1)) > int(pipeline_id):
        raise PublishError(
            f"Skipping stale pipeline {pipeline_id}; a newer pipeline is already published"
        )


def verify_source_pipeline(gitlab: GitLabClient, env: dict[str, str]) -> None:
    branch = gitlab.develop_branch()
    commit = branch.get("commit", {})
    if branch.get("name") != EXPECTED_BRANCH or commit.get("id") != env["CI_COMMIT_SHA"]:
        raise PublishError(
            f"Skipping stale pipeline {env['CI_PIPELINE_ID']}; "
            "canonical develop has advanced to a different commit"
        )


def release_description(
    asset_url: str, pipeline_id: str, commit_sha: str, apk_version: str, checksum: str
) -> str:
    pipeline_url = (
        "https://git.tak.gov/aegorsuch/atak-plugin-weartak-companion"
        f"/-/pipelines/{pipeline_id}"
    )
    return "\n".join(
        [
            RELEASE_OWNERSHIP_MARKER,
            "",
            "Latest signed CIV distributable from the canonical TAK.gov `develop` pipeline.",
            "",
            f"- APK: [Download signed CIV plugin]({asset_url})",
            f"- Plugin version: `{apk_version}`",
            f"- Source commit: [`{commit_sha}`](https://git.tak.gov/aegorsuch/atak-plugin-weartak-companion/-/commit/{commit_sha})",
            f"- Pipeline: [#{pipeline_id}]({pipeline_url})",
            f"- SHA-256: `{checksum}`",
        ]
    )


def publish(env: dict[str, str], gitlab: GitLabClient, github: GitHubClient) -> str:
    verify_source_pipeline(gitlab, env)
    github.require_existing_tag()
    release = github.get_release()
    previous_release_owned = (
        release is not None
        and RELEASE_OWNERSHIP_MARKER in str(release.get("body", ""))
    )
    if release is not None:
        if (
            release.get("tag_name") != RELEASE_TAG
            or release.get("name") != RELEASE_TITLE
            or release.get("draft") is not False
            or release.get("prerelease") is not False
        ):
            raise PublishError("Existing release does not match the expected stable rolling release")
        verify_release_order(release, env["CI_PIPELINE_ID"])

    project_dir = Path(env["CI_PROJECT_DIR"])
    apk = select_signed_apk(project_dir)
    verify_signer(apk)
    apk_bytes = apk.read_bytes()
    checksum = hashlib.sha256(apk_bytes).hexdigest()
    pipeline_id = env["CI_PIPELINE_ID"]
    asset_name = f"{ASSET_PREFIX}{pipeline_id}.apk"
    plugin_version = read_plugin_version(apk)

    if release is None:
        release = github.create_release()
    if (
        release.get("tag_name") != RELEASE_TAG
        or release.get("name") != RELEASE_TITLE
        or release.get("draft") is not False
        or release.get("prerelease") is not False
    ):
        raise PublishError("Existing release does not match the expected stable rolling release")

    release_id = int(release["id"])
    assets = github.list_assets(release_id)
    current_asset = next((asset for asset in assets if asset.get("name") == asset_name), None)
    asset_label = f"{ASSET_LABEL_PREFIX}{checksum}"
    if current_asset is not None:
        if current_asset.get("label") != asset_label:
            raise PublishError("An asset with this pipeline name is not marked as publisher-owned")
        if github.download_asset(int(current_asset["id"])) != apk_bytes:
            raise PublishError("An asset for this pipeline already exists with different contents")
    else:
        github.upload_asset(release, asset_name, asset_label, apk_bytes)
        assets = github.list_assets(release_id)
        current_asset = next((asset for asset in assets if asset.get("name") == asset_name), None)
        if current_asset is None:
            raise PublishError("Uploaded APK is missing from the rolling release")
        if current_asset.get("label") != asset_label:
            raise PublishError("Uploaded APK is missing its publisher ownership label")
        if github.download_asset(int(current_asset["id"])) != apk_bytes:
            raise PublishError("Uploaded APK contents do not match the signed pipeline artifact")

    description = release_description(
        current_asset["browser_download_url"],
        pipeline_id,
        env["CI_COMMIT_SHA"],
        plugin_version,
        checksum,
    )
    github.update_release_body(release_id, description)
    github.dispatch_release_test(release_id, pipeline_id, description)

    managed_asset = re.compile(re.escape(ASSET_PREFIX) + r"[0-9]+\.apk\Z")
    if previous_release_owned:
        for asset in assets:
            if asset.get("id") == current_asset.get("id"):
                continue
            if not managed_asset.fullmatch(str(asset.get("name", ""))):
                continue
            label = str(asset.get("label", ""))
            hash_match = re.fullmatch(
                re.escape(ASSET_LABEL_PREFIX) + r"([0-9a-f]{64})", label
            )
            if hash_match is None:
                continue
            old_content = github.download_asset(int(asset["id"]))
            if hashlib.sha256(old_content).hexdigest() != hash_match.group(1):
                continue
            github.delete_asset(int(asset["id"]))
    return asset_name


def main() -> int:
    try:
        env = require_environment(dict(os.environ))
        gitlab = GitLabClient(
            env["CI_API_V4_URL"], env["CI_PROJECT_ID"], env["CI_JOB_TOKEN"]
        )
        github = GitHubClient(env["GITHUB_REPOSITORY"], env["GITHUB_RELEASE_TOKEN"])
        asset_name = publish(env, gitlab, github)
        print(f"Published {asset_name} to the stable rolling GitHub release.")
        return 0
    except PublishError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
