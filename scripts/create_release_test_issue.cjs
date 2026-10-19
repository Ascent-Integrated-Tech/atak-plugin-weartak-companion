const { readFileSync } = require('node:fs');
const { join } = require('node:path');

async function createReleaseTestIssue({ github, context, core, tester }) {
  const release = context.payload.release;
  if (release.draft) {
    core.info('Draft releases do not receive testing assignments.');
    return;
  }
  if (typeof tester !== 'string' || !/^[a-z\d](?:[a-z\d-]{0,37}[a-z\d])?$/i.test(tester)) {
    throw new Error('Set the RELEASE_TESTER repository variable to an authorized tester GitHub username.');
  }

  const marker = `<!-- weartak-release-testing:${release.id} -->`;
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
    title: `Release testing: ${release.tag_name}`,
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
