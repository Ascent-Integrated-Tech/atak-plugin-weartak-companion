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
