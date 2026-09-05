/*
 * RVF console.
 *
 * Same-origin calls only. The gateway has already authenticated the browser
 * and injects the X-AUTH-* headers the server reads, so there is no token
 * handling here and nothing secret in this file.
 */
'use strict';

// The console is served from /ui/, the API sits at the root.
const API = '..';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

/* Every value from a report goes through this. Reports carry SQL text and
 * content from the release, so inserting them as HTML would be an injection
 * waiting to happen. */
const esc = (v) => String(v ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const num = (n) => (typeof n === 'number' ? n.toLocaleString() : '0');

function toast(message, ok = false) {
  const el = $('#toast');
  el.textContent = message;
  el.classList.toggle('ok', ok);
  el.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.hidden = true; }, ok ? 4000 : 9000);
}

/* A 302 to the identity provider means the session has gone. fetch follows it
 * and we end up parsing a login page as JSON, so detect it and say so plainly
 * rather than reporting a syntax error. */
async function api(path, options) {
  const res = await fetch(`${API}${path}`, { credentials: 'same-origin', ...options });
  if (res.redirected && /openid-connect|protocol\/openid/.test(res.url)) {
    throw new Error('Your session has ended. Reload the page to sign in again.');
  }
  if (!res.ok) {
    let detail = '';
    try { detail = (await res.text()).slice(0, 300); } catch { /* body already gone */ }
    throw new Error(`${res.status} ${res.statusText}${detail ? ` - ${detail}` : ''}`);
  }
  const type = res.headers.get('content-type') || '';
  return type.includes('json') ? res.json() : res.text();
}

/* ------------------------------------------------------------------- tabs */

$$('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    $$('.tab').forEach((t) => { t.classList.remove('active'); t.setAttribute('aria-selected', 'false'); });
    $$('.panel').forEach((p) => p.classList.remove('active'));
    tab.classList.add('active');
    tab.setAttribute('aria-selected', 'true');
    $('#' + tab.dataset.panel).classList.add('active');
    // Load on first view rather than at start-up: it is a directory walk on a
    // network file share, so it should not be paid for by someone who only
    // wants to submit a run.
    if (tab.dataset.panel === 'panel-open' && !loadRuns.done) loadRuns();
  });
});

/* --------------------------------------------------------------- start-up */

function defaults() {
  const stamp = Date.now();
  $('#runId').value = String(stamp);
  $('#storageLocation').value = `ui_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}_${String(stamp).slice(-6)}`;
}

async function loadVersion() {
  try {
    const v = await api('/version');
    const text = typeof v === 'string' ? v : (v.version || JSON.stringify(v));
    $('#serverInfo').textContent = `connected \u00b7 ${text}`;
    $('#footVersion').textContent = `RVF ${text}`;
  } catch (e) {
    $('#serverInfo').textContent = 'not connected';
    toast(e.message);
  }
}

/* The group names come from the server rather than a list in this file, so a
 * group added to the store shows up here with no change to the console. */
async function loadGroups() {
  const box = $('#groups');
  try {
    const raw = await api('/groups');
    const list = (Array.isArray(raw) ? raw : raw.content || raw.items || [])
      .map((g) => (typeof g === 'string' ? g : g.name || g.id))
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b));

    if (!list.length) { box.innerHTML = '<p class="muted">This server has no assertion groups.</p>'; return; }

    box.innerHTML = list.map((name) => `
      <label class="check">
        <input type="checkbox" name="group" value="${esc(name)}">
        <span>${esc(name)}</span>
      </label>`).join('');

    // Preselect the group the nightly leans on, when it is present.
    const first = $$('input[name=group]', box).find((i) => i.value === 'file-centric-validation');
    if (first) first.checked = true;
  } catch (e) {
    box.innerHTML = `<p class="muted">Could not load groups: ${esc(e.message)}</p>`;
  }
}

/* ------------------------------------------------------------- the upload */

const drop = $('#drop');
const fileInput = $('#file');

['dragenter', 'dragover'].forEach((ev) =>
  drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave', 'drop'].forEach((ev) =>
  drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove('over'); }));

drop.addEventListener('drop', (e) => {
  const file = e.dataTransfer?.files?.[0];
  if (!file) return;
  const data = new DataTransfer();
  data.items.add(file);
  fileInput.files = data.files;
  showFile();
});
fileInput.addEventListener('change', showFile);

function showFile() {
  const f = fileInput.files?.[0];
  $('#dropFile').textContent = f ? `${f.name} (${(f.size / 1048576).toFixed(1)} MB)` : '';
}

$('#enableDrools').addEventListener('change', (e) => { $('#droolsGroupsWrap').hidden = !e.target.checked; });
$('#enableMrcmValidation').addEventListener('change', (e) => { $('#mrcmHint').hidden = !e.target.checked; });

/* ------------------------------------------------------------------ submit */

$('#runForm').addEventListener('submit', async (e) => {
  e.preventDefault();

  const groups = $$('input[name=group]:checked').map((i) => i.value);
  if (!groups.length) { toast('Choose at least one assertion group.'); return; }

  const runId = $('#runId').value.trim();
  const storageLocation = $('#storageLocation').value.trim();

  const body = new FormData();
  body.append('file', fileInput.files[0]);
  body.append('runId', runId);
  body.append('storageLocation', storageLocation);
  body.append('groups', groups.join(','));
  body.append('writeSuccesses', $('#writeSuccesses').checked);
  body.append('rf2DeltaOnly', $('#rf2DeltaOnly').checked);
  body.append('releaseAsAnEdition', $('#releaseAsAnEdition').checked);
  body.append('standAloneProduct', $('#standAloneProduct').checked);
  body.append('enableDrools', $('#enableDrools').checked);
  body.append('enableMrcmValidation', $('#enableMrcmValidation').checked);
  body.append('failureExportMax', $('#failureExportMax').value || '10');

  const manifest = $('#manifest').files?.[0];
  if (manifest) body.append('manifest', manifest);

  const optional = {
    previousRelease: $('#previousRelease').value.trim(),
    effectiveTime: $('#effectiveTime').value.trim(),
    droolsRulesGroups: $('#enableDrools').checked ? $('#droolsRulesGroups').value.trim() : '',
  };
  for (const [k, v] of Object.entries(optional)) if (v) body.append(k, v);

  const btn = $('#submitBtn');
  btn.disabled = true;
  btn.textContent = 'Uploading\u2026';

  try {
    await api('/run-post', { method: 'POST', body });
    toast('Validation submitted.', true);
    // The list is now stale, so make the next visit to that tab reload it.
    loadRuns.done = false;
    watch(runId, storageLocation);
    defaults();                       // so a second run cannot reuse the id
  } catch (err) {
    toast(`Could not submit: ${err.message}`);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Start validation';
  }
});

$('#openForm').addEventListener('submit', (e) => {
  e.preventDefault();
  watch($('#openRunId').value.trim(), $('#openStorage').value.trim(), true);
});

/* ----------------------------------------------------------- the run list */

let allRuns = [];

const AGO = [[86400000, 'd'], [3600000, 'h'], [60000, 'm']];
function ago(ms) {
  if (!ms) return '';
  const d = Date.now() - ms;
  if (d < 60000) return 'just now';
  for (const [unit, label] of AGO) {
    if (d >= unit) return `${Math.floor(d / unit)}${label} ago`;
  }
  return '';
}

function statePill(state, failures) {
  if (state === 'COMPLETE') {
    return failures > 0
      ? `<span class="pill bad">${num(failures)} failed</span>`
      : '<span class="pill ok">clean</span>';
  }
  if (state === 'FAILED') return '<span class="pill bad">run failed</span>';
  if (state === 'QUEUED' || state === 'READY') return '<span class="pill warn">queued</span>';
  if (state === 'RUNNING') return '<span class="pill warn">running</span>';
  return `<span class="pill off">${esc(state || 'unknown')}</span>`;
}

async function loadRuns() {
  loadRuns.done = true;
  const box = $('#runList');
  box.innerHTML = '<p class="muted">loading&hellip;</p>';
  try {
    allRuns = await api('/result?limit=200');
    drawRuns();
  } catch (e) {
    // A server without the listing endpoint answers 404. Say what to do rather
    // than leave the panel blank.
    box.innerHTML = /404/.test(e.message)
      ? '<p class="muted">This server does not support listing runs. Use the run id below.</p>'
      : `<p class="muted">Could not list runs: ${esc(e.message)}</p>`;
  }
}

function drawRuns() {
  const needle = $('#runFilter').value.trim().toLowerCase();
  const onlyFailures = $('#onlyFailures').checked;

  const rows = allRuns.filter((r) => {
    if (onlyFailures && !(r.totalFailures > 0)) return false;
    if (!needle) return true;
    return [r.storageLocation, r.testFileName, r.groups, r.runId]
      .some((v) => String(v ?? '').toLowerCase().includes(needle));
  });

  if (!rows.length) {
    $('#runList').innerHTML = `<p class="empty">${allRuns.length ? 'No run matches that filter.' : 'No runs on this server yet.'}</p>`;
    return;
  }

  $('#runList').innerHTML = `
    <table class="runs">
      <thead>
        <tr><th>when</th><th>package</th><th>groups</th><th>result</th><th>run id</th><th></th></tr>
      </thead>
      <tbody>
        ${rows.map((r, i) => `
          <tr data-i="${i}"${r.runId ? ' class="openable" tabindex="0" role="button"' : ''}>
            <td>${esc(ago(r.lastModified))}</td>
            <td>${esc(r.testFileName || r.storageLocation)}</td>
            <td class="dim">${esc(r.groups || '')}</td>
            <td>${statePill(r.state, r.totalFailures)}${
              r.totalTestsRun ? ` <span class="dim">${num(r.totalTestsRun)} assertions</span>` : ''}</td>
            <td class="dim">${esc(r.runId ?? '')}</td>
            <td>${r.runId ? '<span class="dim">open &rarr;</span>' : ''}</td>
          </tr>`).join('')}
      </tbody>
    </table>`;

  const open = (i) => {
    const r = rows[i];
    if (r?.runId) watch(String(r.runId), r.storageLocation, true);
  };
  $$('#runList tr.openable').forEach((tr) => {
    tr.addEventListener('click', () => open(Number(tr.dataset.i)));
    tr.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(Number(tr.dataset.i)); }
    });
  });
}

$('#refreshRuns').addEventListener('click', loadRuns);
$('#runFilter').addEventListener('input', drawRuns);
$('#onlyFailures').addEventListener('change', drawRuns);

/* ------------------------------------------------------------- the polling */

let timer = null;
let started = 0;

$('#stopPolling').addEventListener('click', () => {
  clearTimeout(timer);
  $('#statusTitle').textContent = 'Stopped watching';
  $('#progress').classList.add('done');
});

function watch(runId, storageLocation, once = false) {
  clearTimeout(timer);
  started = Date.now();

  const status = $('#status');
  status.hidden = false;
  $('#report').hidden = true;
  $('#progress').classList.remove('done');
  $('#statusTitle').textContent = once ? 'Loading report' : 'Validation running';
  $('#statusMeta').innerHTML = `
    <dt>run id</dt><dd>${esc(runId)}</dd>
    <dt>storage</dt><dd>${esc(storageLocation)}</dd>`;
  status.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  const tick = async () => {
    let data;
    try {
      data = await api(`/result/${encodeURIComponent(runId)}?storageLocation=${encodeURIComponent(storageLocation)}`);
    } catch (err) {
      if (once) { toast(`Could not open that report: ${err.message}`); $('#status').hidden = true; return; }
      // A report that does not exist yet is the normal case while the worker
      // starts, so keep waiting rather than treat it as a failure.
      return schedule();
    }

    const state = data.status || (data.rvfValidationResult?.TestResult ? 'COMPLETE' : 'RUNNING');

    if (state === 'COMPLETE') {
      $('#statusTitle').textContent = 'Validation complete';
      $('#progress').classList.add('done');
      $('#statusHint').textContent = '';
      render(data, runId, storageLocation);
      return;
    }
    if (state === 'FAILED') {
      $('#statusTitle').textContent = 'Validation failed';
      $('#progress').classList.add('done');
      render(data, runId, storageLocation);
      return;
    }
    // Opened from the list and not finished yet: the status panel already says
    // so, and a toast that is contradicted five seconds later reads as an error.
    if (once) { $('#statusTitle').textContent = 'Validation running'; }
    schedule();
  };

  const schedule = () => {
    const mins = Math.floor((Date.now() - started) / 60000);
    $('#statusHint').textContent = mins < 1
      ? 'The first check can take a minute. A worker starts only when the queue is not empty.'
      : `Waiting. ${mins} minute${mins === 1 ? '' : 's'} so far. A full edition takes about 13 minutes.`;
    timer = setTimeout(tick, 5000);
  };

  tick();
}

/* -------------------------------------------------------------- rendering */

const BUCKETS = [
  ['failed', 'assertionsFailed', 'Failed'],
  ['warning', 'assertionsWarning', 'Warnings'],
  ['skipped', 'assertionsSkipped', 'Skipped'],
  ['passed', 'assertionsPassed', 'Passed'],
];

function phasePill(text) {
  const s = String(text);
  if (/disabled/i.test(s)) return '<span class="pill off">off</span>';
  const m = s.match(/Failures count:\s*(\d+)/i);
  if (m) return Number(m[1]) > 0
    ? `<span class="pill bad">${m[1]} failed</span>`
    : '<span class="pill ok">clean</span>';
  return '<span class="pill warn">see message</span>';
}

function itemHtml(item, kind) {
  const count = item.failureCount;
  // -1 is not a count. It means the assertion never finished, which is a fault
  // in the validation rather than a finding about the content.
  const badge = count === -1
    ? '<span class="pill warn">did not complete</span>'
    : (typeof count === 'number' && count > 0 ? `${num(count)} instances` : '');

  const instances = item.firstNInstances || [];
  const cols = instances.length ? Object.keys(instances[0]) : [];

  return `
  <details class="item ${kind}">
    <summary>
      <span class="t">${esc(item.assertionText || item.assertionUuid || 'unnamed assertion')}</span>
      <span class="c">${badge}</span>
    </summary>
    <div class="body">
      ${item.failureMessage ? `<div class="msg">${esc(item.failureMessage)}</div>` : ''}
      ${instances.length ? `
        <table class="inst">
          <thead><tr>${cols.map((c) => `<th>${esc(c)}</th>`).join('')}</tr></thead>
          <tbody>${instances.map((row) => `<tr>${cols.map((c) => `<td>${esc(row[c])}</td>`).join('')}</tr>`).join('')}</tbody>
        </table>
        <p class="meta">first ${instances.length} of ${count === -1 ? 'unknown' : num(count)}</p>` : ''}
      <p class="meta">${esc(item.testType || '')} \u00b7 ${esc(item.testCategory || '')} \u00b7 ${esc(item.assertionUuid || '')}</p>
    </div>
  </details>`;
}

function render(data, runId, storageLocation) {
  const result = data.rvfValidationResult || {};
  const test = result.TestResult || {};
  const summary = result.reportSummary || {};
  const failures = result.failureMessages || [];
  const el = $('#report');

  const incomplete = test.totalTestsIncomplete || 0;

  el.innerHTML = `
    <div class="tiles">
      <div class="tile"><div class="n">${num(test.totalTestsRun)}</div><div class="l">assertions run</div></div>
      <div class="tile ${test.totalFailures ? 'bad' : 'ok'}"><div class="n">${num(test.totalFailures)}</div><div class="l">failures</div></div>
      <div class="tile ${test.totalWarnings ? 'warn' : ''}"><div class="n">${num(test.totalWarnings)}</div><div class="l">warnings</div></div>
      <div class="tile"><div class="n">${num(test.totalSkips)}</div><div class="l">skipped</div></div>
      <div class="tile ${incomplete ? 'warn' : ''}"><div class="n">${num(incomplete)}</div><div class="l">incomplete</div></div>
      <div class="tile"><div class="n">${num(result.totalRF2FilesLoaded)}</div><div class="l">RF2 files</div></div>
    </div>

    ${failures.length ? `<div class="card"><legend>The run reported errors</legend>
      <div class="items">${failures.map((m) => `<div class="msg">${esc(typeof m === 'string' ? m : JSON.stringify(m))}</div>`).join('')}</div></div>` : ''}

    <div class="card">
      <legend>Phases</legend>
      <div class="phases">
        ${Object.entries(summary).map(([name, text]) =>
          `<div class="phase"><b>${esc(name)}</b> ${phasePill(text)} <span class="muted">${esc(text)}</span></div>`).join('')
          || '<p class="muted">No phase summary in this report.</p>'}
      </div>
      <dl class="kv" style="margin-top:14px">
        <dt>run id</dt><dd>${esc(runId)}</dd>
        <dt>storage</dt><dd>${esc(storageLocation)}</dd>
        <dt>package</dt><dd>${esc(result.validationConfig?.testFileName || '')}</dd>
        <dt>started</dt><dd>${esc(result.startTime || '')}</dd>
        <dt>ended</dt><dd>${esc(result.endTime || '')}</dd>
        <dt>duration</dt><dd>${test.timeTakenInSeconds ? `${num(test.timeTakenInSeconds)} s` : ''}</dd>
      </dl>
    </div>

    <div class="filters">
      <div class="seg" id="seg">
        ${BUCKETS.map(([kind, key, label], i) =>
          `<button data-kind="${kind}" class="${i === 0 ? 'on' : ''}">${label} (${num((test[key] || []).length)})</button>`).join('')}
      </div>
      <input type="text" id="filter" placeholder="Filter by assertion text\u2026">
      <button type="button" class="ghost" id="download">Download JSON</button>
    </div>

    <div class="items" id="items"></div>`;

  el.hidden = false;

  let kind = 'failed';

  const draw = () => {
    const key = BUCKETS.find(([k]) => k === kind)[1];
    const needle = $('#filter').value.trim().toLowerCase();
    const rows = (test[key] || []).filter((it) =>
      !needle || String(it.assertionText || '').toLowerCase().includes(needle));

    $('#items').innerHTML = rows.length
      ? rows.map((it) => itemHtml(it, kind)).join('')
      : `<p class="empty">Nothing in this category${needle ? ' for that filter' : ''}.</p>`;
  };

  $$('#seg button').forEach((b) => b.addEventListener('click', () => {
    $$('#seg button').forEach((x) => x.classList.remove('on'));
    b.classList.add('on');
    kind = b.dataset.kind;
    draw();
  }));
  $('#filter').addEventListener('input', draw);

  $('#download').addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `rvf-${runId}.json`;
    a.click();
    URL.revokeObjectURL(url);
  });

  draw();
  el.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/* ------------------------------------------------------------------- boot */

defaults();
loadVersion();
loadGroups();
