const { readFileSync } = require('node:fs');
const { join } = require('node:path');

function verifiedRollingDetails(context, release, pipelineId, asset) {
  const body = release.body;
  if (!asset) {
    const iid = /project pipeline IID `([1-9]\d*)`/.exec(body);
    const version = /^- APK version: `(\d+(?:\.\d+)+)(?:\s[^`\r\n]*)?`/m.exec(body);
    const versionedLink = iid && version &&
      body.includes(`/WearTAK-Companion-${version[1]}-p${iid[1]}.apk)`);
    return body.includes(`- Pipeline: [#${pipelineId}](`) &&
      /^- SHA-256: `[0-9a-f]{64}`$/m.test(body) &&
      (body.includes(`/WearTAK-ATAK-Plugin-civ-release-pipeline-${pipelineId}.apk)`) ||
        versionedLink);
  }
  const name = /^WearTAK-Companion-(\d+(?:\.\d+)+)-p([1-9]\d*)\.apk$/.exec(asset.name || '');
  const legacyName = asset.name === `WearTAK-ATAK-Plugin-civ-release-pipeline-${pipelineId}.apk`;
  const checksum = /^- SHA256: `([0-9a-f]{64})`$/m.exec(body);
  const version = /^- APK version: \*\*([^*\r\n]+)\*\* \(versionCode \*\*([1-9]\d*)\*\*\)$/m.exec(body);
  const build = /^- Build: GitLab pipeline \*\*([1-9]\d*)\*\* \(project pipeline \*\*([1-9]\d*)\*\*\)$/m.exec(body);
  const pipeline = /^- Pipeline: (https:\/\/git\.tak\.gov\/aegorsuch\/atak-plugin-weartak-companion\/-\/pipelines\/[1-9]\d*)$/m.exec(body);
  const source = /^- Source commit: `[0-9a-f]{40}` on TAK\.gov `develop`$/m.test(body);
  if ((!name && !legacyName) || !checksum || !version || !build || !pipeline || !source) return false;
  const baseVersion = /^(\d+(?:\.\d+)+)(?:\s|$)/.exec(version[1]);
  return baseVersion && (!name || baseVersion[1] === name[1]) &&
    asset.label === asset.name && asset.sha256 === checksum[1] &&
    asset.pipeline_iid === build[2] && (!name || name[2] === build[2]) && build[1] === pipelineId &&
    pipeline[1].endsWith(`/pipelines/${pipelineId}`) &&
    body.includes('<!-- /weartak-companion-rolling-release:managed -->') &&
    asset.url === `https://github.com/${context.repo.owner}/${context.repo.repo}/releases/download/tak-gov-ci-cd-pipeline/${asset.name}`;
}

function testRelease(context) {
  if (context.eventName === 'repository_dispatch') {
    const { release, pipeline_id: pipelineId, asset } = context.payload.client_payload || {};
    if (
      !release || release.draft !== false ||
      !Number.isSafeInteger(release.id) || release.id <= 0 ||
      release.tag_name !== 'tak-gov-ci-cd-pipeline' ||
      typeof pipelineId !== 'string' || !/^[1-9]\d*$/.test(pipelineId) ||
      typeof release.body !== 'string' ||
      !release.body.includes('<!-- weartak-companion-rolling-release:managed -->') ||
      !verifiedRollingDetails(context, release, pipelineId, asset)
    ) {
      throw new Error('Invalid verified rolling-release notification.');
    }
    return {
      release,
      asset,
      key: `${release.id}-pipeline-${pipelineId}`,
      title: `Release testing: TAK.gov pipeline ${pipelineId}`,
    };
  }
  const release = context.payload.release;
  // The publisher dispatches only after verifying the rolling APK, not at placeholder creation.
  if (release.draft || release.tag_name === 'tak-gov-ci-cd-pipeline') return null;
  return { release, key: String(release.id), title: `Release testing: ${release.tag_name}` };
}

async function createReleaseTestIssue({ github, context, core, tester }) {
  const candidate = testRelease(context);
  if (!candidate) {
    core.info('No published build to assign; rolling builds require a verified publisher notification.');
    return;
  }
  const { release, asset, key, title } = candidate;
  if (typeof tester !== 'string' || !/^[a-z\d](?:[a-z\d-]{0,37}[a-z\d])?$/i.test(tester)) {
    throw new Error('Set the RELEASE_TESTER repository variable to an authorized tester GitHub username.');
  }

  const marker = `<!-- weartak-release-testing:${key} -->`;
  const repo = context.repo;
  // Include closed issues so reruns cannot recreate a completed checklist.
  for await (const page of github.paginate.iterator(
    github.rest.issues.listForRepo,
    { ...repo, state: 'all', per_page: 100 },
  )) {
    const existing = page.data.find(
      (issue) => !issue.pull_request && (issue.body || '').includes(marker),
    );
    if (existing) {
      if (existing.state === 'open' && !existing.assignees.some(
        (assignee) => assignee.login.toLowerCase() === tester.toLowerCase(),
      )) {
        await github.rest.issues.checkUserCanBeAssigned({ ...repo, assignee: tester });
        const { data: assigned } = await github.rest.issues.addAssignees({
          ...repo, issue_number: existing.number, assignees: [tester],
        });
        if (!assigned.assignees.some(
          (assignee) => assignee.login.toLowerCase() === tester.toLowerCase(),
        )) {
          throw new Error(`Checklist ${assigned.html_url} is still not assigned to ${tester}.`);
        }
        core.info(`Assigned release checklist: ${assigned.html_url}`);
        return assigned;
      }
      core.info(`Checklist already exists: ${existing.html_url}`);
      return existing;
    }
  }

  await github.rest.issues.checkUserCanBeAssigned({ ...repo, assignee: tester });
  const checklist = readFileSync(
    join(__dirname, '..', 'docs', 'release-test-checklist.md'),
    'utf8',
  );
  const body = [
    marker,
    `@${tester}, please test this companion release and record results below.`,
    '',
    `**Release:** [${release.tag_name}](${release.html_url})`,
    '',
    '## Published release details',
    '',
    release.body || 'No release notes were provided.',
    ...(asset ? ['', `**APK:** [${asset.name}](${asset.url})`] : []),
    '',
    '---',
    '',
    checklist,
  ].join('\n');

  const { data: issue } = await github.rest.issues.create({
    ...repo,
    title,
    body,
    assignees: [tester],
  });
  if (!issue.assignees.some((assignee) => assignee.login.toLowerCase() === tester.toLowerCase())) {
    throw new Error(`Checklist ${issue.html_url} was created but not assigned to ${tester}.`);
  }
  core.info(`Assigned release checklist: ${issue.html_url}`);
  return issue;
}

module.exports = { createReleaseTestIssue };
