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
named `WearTAK-ATAK-Plugin-civ-release-pipeline-<pipeline-id>.apk`; the release
description links to the current asset and includes its source commit, pipeline,
plugin version, and SHA-256. Each managed APK has a specific ownership label
containing its SHA-256, and the release description includes an ownership
marker. The new asset is uploaded and downloaded for a byte-for-byte
verification before the release description changes. Cleanup requires that the
release description already carry the ownership marker, plus the exact publisher
label and filename pattern, and a downloaded asset hash matching the hash in
that label. Unlabeled, mismatched, or otherwise unverifiable assets are retained.
Other release assets and every separately tagged release are left untouched. A
failed upload or verification leaves the existing release description and APK
usable.

## GitLab setup

Before enabling the publisher, create `GITHUB_RELEASE_TOKEN` as a masked and
protected CI/CD variable in the canonical
`aegorsuch/atak-plugin-weartak-companion` project. Use a GitHub fine-grained
token restricted to `Ascent-Integrated-Tech/WearTAK-ATAK-Plugin` with only
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
