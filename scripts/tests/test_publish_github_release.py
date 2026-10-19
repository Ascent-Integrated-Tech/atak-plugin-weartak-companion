import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any
from unittest.mock import patch

from scripts import publish_github_release as publisher


class FakeGitLab:
    def __init__(self, branch: dict[str, Any]) -> None:
        self.branch = branch

    def develop_branch(self) -> dict[str, Any]:
        return self.branch


class FakeGitHub:
    def __init__(self, assets: list[dict[str, Any]] | None = None) -> None:
        self.assets = list(assets or [])
        self.release = None
        self.body = "old release body"
        self.deleted: list[int] = []
        self.tag_exists = True
        self.upload_failure = False
        self.release_updates = 0
        self.uploaded: dict[str, bytes] = {}
        self.verification_failure = False
        self.release_creations = 0
        self.test_notifications: list[dict[str, Any]] = []
        self.dispatch_failure = False

    def require_existing_tag(self) -> None:
        if not self.tag_exists:
            raise publisher.PublishError(
                f"Required pre-created GitHub tag {publisher.RELEASE_TAG!r} is missing; "
                "the publisher will not create or move tags"
            )

    def get_release(self) -> dict[str, Any] | None:
        return self.release

    def create_release(self) -> dict[str, Any]:
        self.release_creations += 1
        self.release = {
            "id": 77,
            "tag_name": publisher.RELEASE_TAG,
            "name": publisher.RELEASE_TITLE,
            "draft": False,
            "prerelease": False,
            "upload_url": "https://uploads.github.com/repos/example/release/{?name,label}",
        }
        return self.release

    def list_assets(self, release_id: int) -> list[dict[str, Any]]:
        return list(self.assets)

    def upload_asset(
        self, release: dict[str, Any], name: str, label: str, content: bytes
    ) -> None:
        if self.upload_failure:
            raise publisher.PublishError("simulated upload failure")
        asset = {
            "id": max([item["id"] for item in self.assets] + [0]) + 1,
            "name": name,
            "label": label,
            "browser_download_url": "https://github.com/example/release/" + name,
        }
        self.assets.append(asset)
        self.uploaded[name] = content

    def download_asset(self, asset_id: int) -> bytes:
        if self.verification_failure:
            return b"different package"
        asset = next(item for item in self.assets if item["id"] == asset_id)
        return self.uploaded.get(asset["name"], asset.get("_content", b"old package"))

    def update_release_body(self, release_id: int, body: str) -> None:
        self.body = body
        if self.release:
            self.release["body"] = body
        self.release_updates += 1

    def delete_asset(self, asset_id: int) -> None:
        self.deleted.append(asset_id)
        self.assets = [item for item in self.assets if item["id"] != asset_id]

    def dispatch_release_test(self, release_id: int, pipeline_id: str, body: str) -> None:
        if self.dispatch_failure:
            raise publisher.PublishError("test notification failed")
        if self.body != body:
            raise AssertionError("Notification preceded release metadata publication")
        self.test_notifications.append(
            {"release_id": release_id, "pipeline_id": pipeline_id, "body": body}
        )


def make_env(directory: Path, pipeline_id: str = "100") -> dict[str, str]:
    return {
        "GITHUB_RELEASE_TOKEN": "not-a-real-token",
        "GITHUB_REPOSITORY": publisher.EXPECTED_GITHUB_REPOSITORY,
        "CI_SERVER_HOST": publisher.EXPECTED_SERVER,
        "CI_PROJECT_PATH": publisher.EXPECTED_PROJECT,
        "CI_PROJECT_ID": "6782",
        "CI_COMMIT_BRANCH": publisher.EXPECTED_BRANCH,
        "CI_PIPELINE_SOURCE": "push",
        "CI_PIPELINE_ID": pipeline_id,
        "CI_COMMIT_SHA": "a" * 40,
        "CI_API_V4_URL": "https://git.tak.gov/api/v4",
        "CI_JOB_TOKEN": "not-a-real-job-token",
        "CI_PROJECT_DIR": str(directory),
        "CI_COMMIT_REF_PROTECTED": "true",
    }


def make_apk(
    directory: Path,
    name: str = (
        "ATAK-Plugin-atak-plugin-weartak-companion-5.6.0-abc-5.6.0-civ-release.apk"
    ),
) -> Path:
    apk = directory / name
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
    return apk


def make_gitlab(env: dict[str, str]) -> FakeGitLab:
    return FakeGitLab(
        {"name": "develop", "commit": {"id": env["CI_COMMIT_SHA"]}}
    )


def make_owned_asset(asset_id: int, pipeline_id: str, content: bytes = b"old package") -> dict[str, Any]:
    return {
        "id": asset_id,
        "name": publisher.ASSET_PREFIX + pipeline_id + ".apk",
        "label": publisher.ASSET_LABEL_PREFIX + hashlib.sha256(content).hexdigest(),
        "browser_download_url": f"https://github.com/example/{pipeline_id}.apk",
        "_content": content,
    }


class RollingReleaseTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp_dir.name)
        make_apk(self.directory)
        self.env = make_env(self.directory)
        self.gitlab = make_gitlab(self.env)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_release_writes_use_current_repository_name_without_redirects(self) -> None:
        self.assertEqual(
            publisher.EXPECTED_GITHUB_REPOSITORY,
            "Ascent-Integrated-Tech/atak-plugin-weartak-companion",
        )
        config = (Path(__file__).resolve().parents[2] / ".gitlab-ci.yml").read_text()
        self.assertIn(
            f"GITHUB_REPOSITORY: {publisher.EXPECTED_GITHUB_REPOSITORY}", config
        )
        github = publisher.GitHubClient(publisher.EXPECTED_GITHUB_REPOSITORY, "test-token")
        with patch.object(github, "json_request", return_value=(200, {})) as request:
            github.update_release_body(77, "verified notes")
        self.assertEqual(request.call_args.args, (
            "PATCH",
            "/repos/Ascent-Integrated-Tech/atak-plugin-weartak-companion/releases/77",
        ))

    def test_publishes_only_current_signed_civ_release_asset(self) -> None:
        make_apk(
            self.directory,
            "ATAK-Plugin-atak-plugin-weartak-companion-5.6.0-abc-5.6.0-civ-debug.apk",
        )
        github = FakeGitHub()
        with patch.object(publisher, "verify_signer"):
            asset_name = publisher.publish(self.env, self.gitlab, github)

        self.assertEqual(asset_name, publisher.ASSET_PREFIX + "100.apk")
        self.assertEqual(list(github.uploaded), [asset_name])
        self.assertFalse(github.release["draft"])
        self.assertFalse(github.release["prerelease"])
        self.assertIn("SHA-256:", github.body)
        self.assertIn(publisher.RELEASE_OWNERSHIP_MARKER, github.body)
        self.assertIn("Plugin version: `5.6.0`", github.body)
        self.assertIn("Pipeline: [#100]", github.body)
        self.assertEqual(github.test_notifications, [
            {"release_id": 77, "pipeline_id": "100", "body": github.body}
        ])

    def test_dispatch_failure_is_visible_and_verified_publication_can_be_retried(self) -> None:
        github = FakeGitHub()
        github.dispatch_failure = True
        with patch.object(publisher, "verify_signer"):
            with self.assertRaisesRegex(publisher.PublishError, "test notification failed"):
                publisher.publish(self.env, self.gitlab, github)
            self.assertIn("Pipeline: [#100]", github.body)
            self.assertEqual(len(github.assets), 1)
            github.dispatch_failure = False
            publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(len(github.assets), 1)
        self.assertEqual(len(github.test_notifications), 1)

    def test_dispatch_api_contract_and_failure(self) -> None:
        github = publisher.GitHubClient(publisher.EXPECTED_GITHUB_REPOSITORY, "test-token")
        with patch.object(github, "json_request", return_value=(204, None)) as request:
            github.dispatch_release_test(77, "100", "verified notes")
        method, path = request.call_args.args
        self.assertEqual(method, "POST")
        self.assertEqual(path, f"/repos/{publisher.EXPECTED_GITHUB_REPOSITORY}/dispatches")
        payload = request.call_args.kwargs["body"]
        self.assertEqual(payload["event_type"], "weartak-rolling-release-published")
        self.assertEqual(payload["client_payload"]["pipeline_id"], "100")
        self.assertEqual(payload["client_payload"]["release"]["body"], "verified notes")
        self.assertEqual(payload["client_payload"]["release"]["id"], 77)
        self.assertFalse(payload["client_payload"]["release"]["draft"])
        with patch.object(github, "json_request", return_value=(403, None)):
            with self.assertRaisesRegex(publisher.PublishError, "workflow \\(HTTP 403\\)"):
                github.dispatch_release_test(77, "100", "verified notes")

    def test_missing_precreated_tag_fails_without_creating_release_or_tag(self) -> None:
        github = FakeGitHub()
        github.tag_exists = False
        with self.assertRaisesRegex(publisher.PublishError, "pre-created GitHub tag"):
            publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(github.release_creations, 0)
        self.assertIsNone(github.release)

    def test_stale_pipeline_does_not_mutate_github(self) -> None:
        self.gitlab.branch["commit"]["id"] = "b" * 40
        github = FakeGitHub()
        with self.assertRaisesRegex(publisher.PublishError, "Skipping stale pipeline"):
            publisher.publish(self.env, self.gitlab, github)
        self.assertIsNone(github.release)
        self.assertEqual(github.uploaded, {})
        self.assertEqual(github.test_notifications, [])

    def test_older_duplicate_pipeline_cannot_replace_newer_published_pipeline(self) -> None:
        github = FakeGitHub()
        github.release = {
            "id": 77,
            "tag_name": publisher.RELEASE_TAG,
            "name": publisher.RELEASE_TITLE,
            "draft": False,
            "prerelease": False,
            "body": "- Pipeline: [#101](https://git.tak.gov/example/101)",
            "upload_url": "https://uploads.github.com/repos/example/release/{?name,label}",
        }
        with self.assertRaisesRegex(
            publisher.PublishError, "newer pipeline is already published"
        ):
            publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(github.uploaded, {})

    def test_failed_upload_keeps_previous_asset_and_release_body(self) -> None:
        old = make_owned_asset(1, "99")
        github = FakeGitHub([old])
        github.release = {
            "id": 77,
            "tag_name": publisher.RELEASE_TAG,
            "name": publisher.RELEASE_TITLE,
            "draft": False,
            "prerelease": False,
            "upload_url": "https://uploads.github.com/repos/example/release/{?name,label}",
        }
        github.upload_failure = True
        with patch.object(publisher, "verify_signer"):
            with self.assertRaisesRegex(publisher.PublishError, "simulated upload failure"):
                publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(github.assets, [old])
        self.assertEqual(github.body, "old release body")
        self.assertEqual(github.test_notifications, [])
        self.assertEqual(github.deleted, [])

    def test_wrong_signer_does_not_create_or_change_release(self) -> None:
        github = FakeGitHub()
        with patch.object(
            publisher,
            "verify_signer",
            side_effect=publisher.PublishError("unexpected signer"),
        ):
            with self.assertRaisesRegex(publisher.PublishError, "unexpected signer"):
                publisher.publish(self.env, self.gitlab, github)
        self.assertIsNone(github.release)

    def test_failed_asset_verification_keeps_previous_asset_and_body(self) -> None:
        old = make_owned_asset(1, "99")
        github = FakeGitHub([old])
        github.release = {
            "id": 77,
            "tag_name": publisher.RELEASE_TAG,
            "name": publisher.RELEASE_TITLE,
            "draft": False,
            "prerelease": False,
            "body": publisher.RELEASE_OWNERSHIP_MARKER,
            "upload_url": "https://uploads.github.com/repos/example/release/{?name,label}",
        }
        github.verification_failure = True
        with patch.object(publisher, "verify_signer"):
            with self.assertRaisesRegex(publisher.PublishError, "contents do not match"):
                publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(github.body, "old release body")
        self.assertEqual(github.test_notifications, [])
        self.assertEqual(github.deleted, [])
        self.assertIn(old, github.assets)

    def test_verified_new_asset_replaces_only_owned_previous_asset(self) -> None:
        old = make_owned_asset(1, "99")
        unrelated = {
            "id": 2,
            "name": "manual-notes.txt",
            "browser_download_url": "https://github.com/example/notes.txt",
        }
        github = FakeGitHub([old, unrelated])
        github.release = {
            "id": 77,
            "tag_name": publisher.RELEASE_TAG,
            "name": publisher.RELEASE_TITLE,
            "draft": False,
            "prerelease": False,
            "body": publisher.RELEASE_OWNERSHIP_MARKER,
            "upload_url": "https://uploads.github.com/repos/example/release/{?name,label}",
        }
        with patch.object(publisher, "verify_signer"):
            publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(github.deleted, [1])
        self.assertIn(unrelated, github.assets)
        self.assertEqual(len(github.assets), 2)

    def test_owned_asset_is_retained_without_release_ownership_marker(self) -> None:
        old = make_owned_asset(1, "99")
        github = FakeGitHub([old])
        github.release = {
            "id": 77,
            "tag_name": publisher.RELEASE_TAG,
            "name": publisher.RELEASE_TITLE,
            "draft": False,
            "prerelease": False,
            "upload_url": "https://uploads.github.com/repos/example/release/{?name,label}",
        }
        with patch.object(publisher, "verify_signer"):
            publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(github.deleted, [])
        self.assertIn(old, github.assets)

    def test_matching_name_without_label_is_not_deleted(self) -> None:
        unowned = {
            "id": 1,
            "name": publisher.ASSET_PREFIX + "99.apk",
            "label": "manually uploaded",
            "browser_download_url": "https://github.com/example/manual.apk",
        }
        github = FakeGitHub([unowned])
        github.release = {
            "id": 77,
            "tag_name": publisher.RELEASE_TAG,
            "name": publisher.RELEASE_TITLE,
            "draft": False,
            "prerelease": False,
            "body": publisher.RELEASE_OWNERSHIP_MARKER,
            "upload_url": "https://uploads.github.com/repos/example/release/{?name,label}",
        }
        with patch.object(publisher, "verify_signer"):
            publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(github.deleted, [])
        self.assertIn(unowned, github.assets)

    def test_matching_name_and_label_with_wrong_hash_is_not_deleted(self) -> None:
        tampered = make_owned_asset(1, "99", b"original")
        tampered["_content"] = b"tampered"
        github = FakeGitHub([tampered])
        github.release = {
            "id": 77,
            "tag_name": publisher.RELEASE_TAG,
            "name": publisher.RELEASE_TITLE,
            "draft": False,
            "prerelease": False,
            "body": publisher.RELEASE_OWNERSHIP_MARKER,
            "upload_url": "https://uploads.github.com/repos/example/release/{?name,label}",
        }
        with patch.object(publisher, "verify_signer"):
            publisher.publish(self.env, self.gitlab, github)
        self.assertEqual(github.deleted, [])
        self.assertIn(tampered, github.assets)


if __name__ == "__main__":
    unittest.main()
