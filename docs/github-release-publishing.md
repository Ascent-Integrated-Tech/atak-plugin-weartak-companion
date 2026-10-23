# TAK.gov pipeline release

The protected `develop` push pipeline publishes the signed CIV APK from
`release_build_and_sign` to the stable GitHub release named **TAK.gov CI/CD
Pipeline Release**. Before the first run, a maintainer must pre-create the fixed
GitHub tag `tak-gov-ci-cd-pipeline`. The publisher requires that tag to exist and
never creates or moves tags. It does not require the canonical commit to exist
on the GitHub mirror, which may lag behind TAK.gov. It checks the protected
source project and branch, skips builds for commits superseded on `develop`,
and records pipeline IDs so an older duplicate pipeline cannot replace a newer
published build. It verifies the APK signature against the TAK Product Center
release certificate before upload. The Android App Bundle, debug APKs, source
archives, signing material, logs, reports, and SBOMs are not uploaded release
assets. GitHub's automatic source-code archive links remain a repository-level
GitHub behavior.

The rolling release uses the fixed tag `tak-gov-ci-cd-pipeline`. Each asset is
named `WearTAK-Companion-<version>-p<project-pipeline-IID>.apk`; the release
page offers it in the native Assets section. Its notes include source commit, pipeline,
manifest versionName/versionCode, and SHA-256. The versioned Forge release title
uses the plugin version and project pipeline IID. The IID is required, and the
distributable version must match the signed manifest versionName's version prefix.
The notes match the watch layout: bold manifest version/code and build identifiers,
plain canonical pipeline URL, full source SHA in code style, and a separate checksum.
They explain that GitHub Source code archives use a fixed anchor
tag rather than the authoritative source SHA. Generated notes sit between hidden
ownership markers; manual content outside that block is preserved, including when
migrating the legacy notes that ended at the checksum line.

Each managed APK uses its filename as the native display label, matching the watch
release. Its checksum is stored separately in
GitHub's asset digest and the release description. The description includes an ownership
marker. The new asset is uploaded and downloaded for a byte-for-byte
verification before the release description changes. Cleanup requires that the
release description already carry the ownership marker, plus the exact publisher
label and filename pattern, and a downloaded asset hash matching the GitHub
SHA-256 digest. Legacy `WearTAK Companion managed APK sha256=...` labels are
also recognized using their embedded hash; a verified same-pipeline legacy asset
can be relabeled in place without re-uploading its bytes. Unlabeled, mismatched,
or otherwise unverifiable assets are retained.
The rolling page keeps only the current publisher-owned APK; superseded verified
assets in both the legacy long pipeline-name and versioned namespaces are removed.
Legacy hash labels and the former readable download label are recognized during
migration, but manual/unverifiable assets remain untouched. The one-time live
migration backed up both old APK bytes and metadata before removing extra assets.
A retry of an already published legacy pipeline reuses its original asset/URL
rather than duplicating it. An existing asset
with the same version/IID but different bytes is rejected, never replaced.
Other release assets and every separately tagged release are left untouched. A
failed upload or verification leaves the existing release description and APK
usable.

## GitLab setup

The publisher and CI repository setting must use the current GitHub name
`Ascent-Integrated-Tech/atak-plugin-weartak-companion`. The former
`WearTAK-ATAK-Plugin` name redirects: reads may work, but release metadata PATCH
requests can fail with HTTP 307. Do not work around this by forwarding
authenticated writes to arbitrary redirect destinations.

Before enabling the publisher, create `GITHUB_RELEASE_TOKEN` as a masked and
protected CI/CD variable in the canonical
`aegorsuch/atak-plugin-weartak-companion` project. Use a GitHub fine-grained
token restricted to `Ascent-Integrated-Tech/atak-plugin-weartak-companion` with only
**Contents: read and write** permission. The `develop` branch must remain
protected so GitLab supplies the token only to its protected pipeline. The CI
image must provide Python 3 and Android
`apksigner`; availability must be confirmed before the first live run. The
publisher also uses the same-project `CI_JOB_TOKEN` to read the current
`develop` branch tip; it does not copy local GitHub credentials or require
another GitLab access token.

No GitHub release/tag or CI variable is created by this source change. The first
release is created against the pre-created fixed tag by the first eligible
successful pipeline after the variable is configured and the change is merged
to canonical `develop`.

## Automatic tester checklist

The GitHub Actions workflow `.github/workflows/release-testing.yml` creates an
issue assigned to the explicitly configured tester when a release is published,
including a prerelease. Separately tagged releases receive one checklist per
release ID.

For the fixed rolling release, the TAK.gov publisher sends the
`weartak-rolling-release-published` repository dispatch after the signed APK is
uploaded, downloaded for byte verification, and its release metadata is updated.
The dispatch carries that build's release notes, pipeline ID, download link, and
SHA-256 so queued runs cannot accidentally report a newer build's details.
The workflow creates one checklist per release ID and pipeline ID. Retries do
not recreate existing checklists, including closed ones; an existing open issue
can have a missing assignment repaired without replacing its results.
Different pipeline notifications have independent concurrency groups.

Initial rolling-release placeholder publication is ignored. Asset uploads and
release-note edits alone do not create checklists. Dispatch failures fail the
publisher visibly after leaving the verified release usable; retry the publisher
job to resend the notification. The dispatch acknowledges GitHub's acceptance,
not checklist creation: inspect the **Release testing checklist** Actions run
for assignment or workflow errors. Rerun that workflow after fixing them.
Later pipelines still replace old APK assets, so testers should download the
assigned build promptly and always record its filename/hash.

Issues include a snapshot of the release notes and the checklist in
[`release-test-checklist.md`](release-test-checklist.md). Edit that file to update
future test assignments. The workflow reads its helper and checklist from the
repository default branch, not the release tag. Notification delivery follows
the tester's GitHub notification settings; no email or messaging integration is
configured.

Before enabling:

- Merge these files into GitHub's default branch (`develop`) and enable Actions
  and Issues for the repository. Rolling-release dispatch runs use the workflow
  on the default branch, so the fixed tag does not need to contain it or move.
  Deploy the publisher change to canonical TAK.gov `develop` as well; updating
  GitHub's mirror alone does not change the canonical publisher.
  For separately tagged releases, the published tag must also contain
  `.github/workflows/release-testing.yml`, because GitHub loads release-triggered
  workflows from the tagged commit. Publishing an older tag, including a fixed
  release tag that predates this workflow, will not trigger it; checking
  out the default branch only takes effect after a workflow run starts.
- Have a maintainer explicitly authorize the recipient by setting the repository
  Actions variable `RELEASE_TESTER` to the tester's GitHub username (without `@`).
  No recipient is configured by this source change.
- Grant that tester repository access and verify they are eligible for issue
  assignment. The workflow fails visibly before issue creation if configuration
  or eligibility checks fail; it does not silently create an unassigned issue.
- Allow the workflow's `GITHUB_TOKEN` to read contents and write issues. No new
  secret or additional permission for the TAK.gov publisher token is needed.
  The existing publisher token's **Contents: read and write** permission also
  permits repository dispatch; no Actions permission is needed for that token.
  Normal release events must come from a user/token or GitHub App; releases
  made using another workflow's `GITHUB_TOKEN` do not trigger this workflow.

This source change does not grant access, alter notification preferences, or
create a live test issue. After merging, authorizing the recipient, and granting
access, publish a tagged release or complete a new rolling publisher pipeline.
Check **Release testing checklist** in Actions and
the assigned issue. A failed run can be rerun once the access or configuration
problem is resolved.

Local helper checks: `node --test scripts/tests/test_create_release_test_issue.cjs`.
Publisher checks: `python -m unittest discover -s scripts/tests -p test_publish_github_release.py`.
