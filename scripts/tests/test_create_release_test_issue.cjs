const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createReleaseTestIssue } = require('../create_release_test_issue.cjs');

function harness(pages = [[]]) {
  const created = [];
  const eligibilityChecks = [];
  const listCalls = [];
  const rest = {
    issues: {
      listForRepo: () => {},
      checkUserCanBeAssigned: async (params) => eligibilityChecks.push(params),
      create: async (params) => {
        created.push(params);
        return {
          data: {
            ...params,
            html_url: 'https://github.com/example/repo/issues/7',
            assignees: params.assignees.map((login) => ({ login })),
          },
        };
      },
    },
  };
  return {
    created,
    eligibilityChecks,
    listCalls,
    args: {
      tester: 'test-user',
      github: {
        rest,
        paginate: {
          iterator: async function* (method, params) {
            assert.equal(method, rest.issues.listForRepo);
            listCalls.push(params);
            for (const data of pages) yield { data };
          },
        },
      },
      context: {
        repo: { owner: 'example', repo: 'repo' },
        payload: {
          release: {
            id: 42,
            tag_name: 'v1.2.3',
            html_url: 'https://github.com/example/repo/releases/tag/v1.2.3',
            body: 'Release notes',
            draft: false,
          },
        },
      },
      core: { info: () => {} },
    },
  };
}

test('creates assigned issue with release details and editable checklist', async () => {
  const h = harness();
  await createReleaseTestIssue(h.args);
  assert.equal(h.created.length, 1);
  assert.equal(h.created[0].title, 'Release testing: v1.2.3');
  assert.deepEqual(h.created[0].assignees, ['test-user']);
  assert.equal(h.eligibilityChecks[0].assignee, 'test-user');
  assert.match(h.created[0].body, /weartak-release-testing:42/);
  assert.match(h.created[0].body, /Release notes/);
  assert.match(h.created[0].body, /releases\/tag\/v1\.2\.3/);
  assert.match(h.created[0].body, /- \[ \] \*\*Connect and recover/);
  assert.match(h.created[0].body, /## Results/);
  assert.equal(h.listCalls[0].state, 'all');
});

test('finds completed checklists beyond the first page without recreating them', async () => {
  const h = harness([[], [{
    state: 'closed', body: '<!-- weartak-release-testing:42 -->',
    html_url: 'https://github.com/example/repo/issues/1',
  }]]);
  await createReleaseTestIssue(h.args);
  assert.equal(h.created.length, 0);
  assert.equal(h.eligibilityChecks.length, 0);
});

test('ignores matching pull requests and other release checklists', async () => {
  const h = harness([[
    { body: '<!-- weartak-release-testing:41 -->' },
    { body: '<!-- weartak-release-testing:42 -->', pull_request: {} },
  ]]);
  await createReleaseTestIssue(h.args);
  assert.equal(h.created.length, 1);
});

test('missing or invalid tester configuration fails before API writes', async () => {
  for (const tester of [undefined, '', '@test-user', 'user\ninjected']) {
    const h = harness();
    h.args.tester = tester;
    await assert.rejects(createReleaseTestIssue(h.args), /RELEASE_TESTER/);
    assert.equal(h.created.length, 0);
    assert.equal(h.eligibilityChecks.length, 0);
  }
});

test('unavailable assignee fails explicitly before creating an issue', async () => {
  const h = harness();
  h.args.github.rest.issues.checkUserCanBeAssigned = async () => {
    throw new Error('Assignee is not eligible (404)');
  };
  await assert.rejects(createReleaseTestIssue(h.args), /not eligible/);
  assert.equal(h.created.length, 0);
});

test('API errors and missing assignment are not reported as success', async () => {
  const h = harness();
  h.args.github.rest.issues.create = async () => { throw new Error('API unavailable'); };
  await assert.rejects(createReleaseTestIssue(h.args), /API unavailable/);
  h.args.github.rest.issues.create = async () => ({
    data: { html_url: 'https://github.com/example/repo/issues/7', assignees: [] },
  });
  await assert.rejects(createReleaseTestIssue(h.args), /not assigned/);
});

test('prereleases are included and absent notes are handled explicitly', async () => {
  const h = harness();
  h.args.context.payload.release.prerelease = true;
  h.args.context.payload.release.body = null;
  await createReleaseTestIssue(h.args);
  assert.match(h.created[0].body, /No release notes were provided/);
});

test('drafts perform no API writes', async () => {
  const h = harness();
  h.args.context.payload.release.draft = true;
  await createReleaseTestIssue(h.args);
  assert.equal(h.created.length, 0);
  assert.equal(h.eligibilityChecks.length, 0);
});

function rollingHarness(pipelineId = '100', pages) {
  const h = harness(pages);
  h.args.context.eventName = 'repository_dispatch';
  h.args.context.payload = {
    action: 'weartak-rolling-release-published',
    client_payload: {
      pipeline_id: pipelineId,
      release: {
        id: 42,
        tag_name: 'tak-gov-ci-cd-pipeline',
        draft: false,
        html_url: 'https://github.com/example/repo/releases/tag/tak-gov-ci-cd-pipeline',
        body: [
          '<!-- weartak-companion-rolling-release:managed -->',
          `- APK: [Download signed CIV plugin](https://github.com/example/repo/releases/download/tak-gov-ci-cd-pipeline/WearTAK-ATAK-Plugin-civ-release-pipeline-${pipelineId}.apk)`,
          '- Plugin version: `5.6.0`',
          `- Pipeline: [#${pipelineId}](https://git.tak.gov/pipeline/${pipelineId})`,
          `- SHA-256: \`${'a'.repeat(64)}\``,
        ].join('\n'),
      },
    },
  };
  return h;
}

test('verified rolling notification creates a pipeline-specific assigned checklist', async () => {
  const h = rollingHarness();
  await createReleaseTestIssue(h.args);
  assert.equal(h.created[0].title, 'Release testing: TAK.gov pipeline 100');
  assert.match(h.created[0].body, /<!-- weartak-release-testing:42-pipeline-100 -->/);
  assert.match(h.created[0].body, /pipeline-100\.apk/);
  assert.match(h.created[0].body, /SHA-256/);
  assert.deepEqual(h.created[0].assignees, ['test-user']);
});

test('duplicate notification does not recreate a closed pipeline checklist', async () => {
  const h = rollingHarness('100', [[{
    state: 'closed',
    body: '<!-- weartak-release-testing:42-pipeline-100 -->',
    html_url: 'https://github.com/example/repo/issues/7',
  }]]);
  await createReleaseTestIssue(h.args);
  assert.equal(h.created.length, 0);
});

test('new pipeline gets a checklist despite prior pipeline and legacy release issues', async () => {
  const h = rollingHarness('101', [[
    { body: '<!-- weartak-release-testing:42-pipeline-100 -->' },
    { body: '<!-- weartak-release-testing:42 -->' },
  ]]);
  await createReleaseTestIssue(h.args);
  assert.equal(h.created[0].title, 'Release testing: TAK.gov pipeline 101');
});

test('queued notifications retain their own build snapshot, not latest release notes', async () => {
  const older = rollingHarness('100');
  const newer = rollingHarness('101');
  await createReleaseTestIssue(newer.args);
  await createReleaseTestIssue(older.args);
  assert.match(older.created[0].body, /pipeline-100\.apk/);
  assert.doesNotMatch(older.created[0].body, /pipeline-101\.apk/);
});

test('initial rolling release publication skips assignment until verified dispatch', async () => {
  const h = harness();
  h.args.context.payload.release.tag_name = 'tak-gov-ci-cd-pipeline';
  await createReleaseTestIssue(h.args);
  assert.equal(h.created.length, 0);
  assert.equal(h.listCalls.length, 0);
});

test('malformed and unverified rolling notifications fail before API calls', async () => {
  const mutations = [
    (p) => { p.pipeline_id = '101'; },
    (p) => { p.pipeline_id = '../100'; },
    (p) => { p.release.draft = true; },
    (p) => { p.release.tag_name = 'other'; },
    (p) => { p.release.id = '42'; },
    (p) => { p.release.body = p.release.body.replace('managed', 'unowned'); },
    (p) => { p.release.body = p.release.body.replace('SHA-256', 'missing'); },
    (p) => { p.release.body = p.release.body.replace('pipeline-100.apk', 'pipeline-99.apk'); },
  ];
  for (const mutate of mutations) {
    const h = rollingHarness();
    mutate(h.args.context.payload.client_payload);
    await assert.rejects(createReleaseTestIssue(h.args), /Invalid verified/);
    assert.equal(h.created.length, 0);
    assert.equal(h.listCalls.length, 0);
  }
});

test('rerun repairs assignment on existing open pipeline issue without replacing results', async () => {
  const h = rollingHarness('100', [[{
    number: 7,
    state: 'open',
    assignees: [],
    body: '<!-- weartak-release-testing:42-pipeline-100 -->\n- [x] Tested',
    html_url: 'https://github.com/example/repo/issues/7',
  }]]);
  let assigned;
  h.args.github.rest.issues.addAssignees = async (params) => {
    assigned = params;
    return { data: { html_url: 'https://github.com/example/repo/issues/7',
      assignees: [{ login: 'test-user' }] } };
  };
  await createReleaseTestIssue(h.args);
  assert.equal(h.created.length, 0);
  assert.equal(assigned.issue_number, 7);
  assert.deepEqual(assigned.assignees, ['test-user']);
});
