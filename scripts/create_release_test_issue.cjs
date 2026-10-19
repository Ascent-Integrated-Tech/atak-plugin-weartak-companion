const { readFileSync } = require('node:fs');
const { join } = require('node:path');

function testRelease(context) {
  if (context.eventName === 'repository_dispatch') {
    const { release, pipeline_id: pipelineId } = context.payload.client_payload;
    if (
      !release || release.draft !== false ||
      !Number.isSafeInteger(release.id) || release.id <= 0 ||
      release.tag_name !== 'tak-gov-ci-cd-pipeline' ||
      typeof pipelineId !== 'string' || !/^[1-9]\d*$/.test(pipelineId) ||
      typeof release.body !== 'string' ||
      !release.body.includes('<!-- weartak-companion-rolling-release:managed -->') ||
      !release.body.includes(`- Pipeline: [#${pipelineId}](`) ||
      !/^- SHA-256: `[0-9a-f]{64}`$/m.test(release.body) ||
      !release.body.includes(`/WearTAK-ATAK-Plugin-civ-release-pipeline-${pipelineId}.apk)`)
    ) {
      throw new Error('Invalid verified rolling-release notification.');
    }
    return {
      release,
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
  const { release, key, title } = candidate;
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
