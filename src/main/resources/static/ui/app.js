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
    if (tab.dataset.panel === 'panel-releases' && !loadReleases.done) loadReleases();
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
  previewPrevious();
}

$('#enableDrools').addEventListener('change', (e) => { $('#droolsGroupsWrap').hidden = !e.target.checked; });
$('#enableMrcmValidation').addEventListener('change', (e) => { $('#mrcmHint').hidden = !e.target.checked; });

/* ----------------------------------------------------------------- upload */

/* fetch() cannot report upload progress - there is no event for it - so a
 * release of several hundred megabytes appeared to hang with a disabled button
 * and nothing else. XMLHttpRequest still exposes upload progress, so it is used
 * for the two requests that carry a file. Everything else stays on fetch. */
function upload(path, body, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    upload.current = xhr;
    xhr.open('POST', `${API}${path}`);
    xhr.withCredentials = true;

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable) onProgress(e.loaded, e.total);
    });
    // The last byte leaving is not the end: the server still has to accept the
    // release and enqueue it. Say that, rather than sit at 100% looking stuck.
    xhr.upload.addEventListener('load', () => onProgress(-1, -1));

    xhr.addEventListener('load', () => {
      upload.current = null;
      if (/openid-connect|protocol\/openid/.test(xhr.responseURL || '')) {
        reject(new Error('Your session has ended. Reload the page to sign in again.'));
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.responseText);
      } else {
        reject(new Error(`${xhr.status} ${xhr.statusText}`
          + (xhr.responseText ? ` - ${xhr.responseText.slice(0, 200)}` : '')));
      }
    });
    xhr.addEventListener('error', () => { upload.current = null; reject(new Error('The connection failed during the upload')); });
    xhr.addEventListener('abort', () => { upload.current = null; reject(new Error('Upload cancelled')); });
    xhr.send(body);
  });
}

const MB = 1048576;

/* Renders into a container: a bar, a byte count, and a cancel button. */
function progressUI(container, label) {
  container.hidden = false;
  container.innerHTML = `
    <div class="uprogress">
      <div class="ubar"><div class="ufill" style="width:0%"></div></div>
      <div class="urow">
        <span class="utext">${esc(label)}</span>
        <button type="button" class="ghost ucancel">Cancel</button>
      </div>
    </div>`;
  container.querySelector('.ucancel').addEventListener('click', () => upload.current?.abort());
  const fill = container.querySelector('.ufill');
  const text = container.querySelector('.utext');
  return (loaded, total) => {
    if (loaded < 0) {
      fill.style.width = '100%';
      fill.classList.add('waiting');
      text.textContent = 'uploaded - waiting for the server to accept it\u2026';
      container.querySelector('.ucancel').hidden = true;
      return;
    }
    const pct = total ? Math.round((loaded / total) * 100) : 0;
    fill.style.width = `${pct}%`;
    text.textContent = `${(loaded / MB).toFixed(0)} of ${(total / MB).toFixed(0)} MB \u00b7 ${pct}%`;
  };
}


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

  // '__auto__' is resolved before submitting, so what goes on the wire is
  // always a real filename or nothing at all.
  let previous = $('#previousRelease').value;
  if (previous === '__auto__') {
    previous = (await detectPrevious(fileInput.files[0]?.name)) || '';
  }

  const optional = {
    previousRelease: previous,
    effectiveTime: $('#effectiveTime').value.trim(),
    droolsRulesGroups: $('#enableDrools').checked ? $('#droolsRulesGroups').value.trim() : '',
  };
  for (const [k, v] of Object.entries(optional)) if (v) body.append(k, v);

  const btn = $('#submitBtn');
  btn.disabled = true;
  btn.textContent = 'Uploading\u2026';

  try {
    await upload('/run-post', body, progressUI($('#submitProgress'), 'starting\u2026'));
    $('#submitProgress').hidden = true;
    toast('Validation submitted.', true);
    // The list is now stale, so make the next visit to that tab reload it.
    loadRuns.done = false;
    watch(runId, storageLocation);
    defaults();                       // so a second run cannot reuse the id
  } catch (err) {
    $('#submitProgress').hidden = true;
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

/* A report is something people link TO - from an ADO build summary, a chat
 * message, a ticket. Without this the link can only land on the console and
 * leave the reader retyping a run id and a storage location off a build log,
 * which is exactly the friction the link was meant to remove.
 *
 * Deliberately a query string rather than a fragment: the fragment is not sent
 * to the server, and these URLs get pasted into tools that rewrite or strip
 * it. Both parameters are required - a run id without its storage location
 * does not identify a report. */
function openFromLink() {
  const q = new URLSearchParams(location.search);
  const runId = (q.get('run') || '').trim();
  const storage = (q.get('storage') || '').trim();
  if (!runId || !storage) return;

  $('#openRunId').value = runId;
  $('#openStorage').value = storage;
  // Reuse the tab's own click handler so the panel is activated and the run
  // list is lazily loaded by exactly the same path as a human click.
  $$('.tab').find((t) => t.dataset.panel === 'panel-open').click();
  watch(runId, storage, true);
}

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

async function loadRuns(opts = {}) {
  loadRuns.done = true;
  const box = $('#runList');
  // A poll must not blank the table it is refreshing.
  if (!opts.quiet) box.innerHTML = '<p class="muted">loading&hellip;</p>';
  try {
    allRuns = await api('/result?limit=200');
    drawRunning();
    drawRuns();
    scheduleRunsRefresh();
  } catch (e) {
    // A server without the listing endpoint answers 404. Say what to do rather
    // than leave the panel blank.
    box.innerHTML = /404/.test(e.message)
      ? '<p class="muted">This server does not support listing runs. Use the run id below.</p>'
      : `<p class="muted">Could not list runs: ${esc(e.message)}</p>`;
  }
}

const IN_FLIGHT = new Set(['QUEUED', 'READY', 'RUNNING']);

/* A run in flight is the thing someone is most likely to have come to see, so
 * it gets its own panel above the list, and a count on the tab so it is visible
 * from anywhere in the console. */
function drawRunning() {
  const running = allRuns.filter((r) => IN_FLIGHT.has(r.state));

  const tab = $$('.tab').find((x) => x.dataset.panel === 'panel-open');
  const label = tab.textContent.replace(/\s*\d+$/, '').trim();
  tab.innerHTML = running.length
    ? `${esc(label)}<span class="badge">${running.length}</span>`
    : esc(label);

  $('#runningCard').hidden = running.length === 0;
  if (!running.length) return;

  $('#runningNote').textContent = 'refreshing every 10 seconds';
  $('#runningList').innerHTML = running.map((r) => `
    <div class="job">
      <span class="pulse" aria-hidden="true"></span>
      <span class="jname">${esc(r.testFileName || r.storageLocation)}</span>
      <span class="jphase">${esc(r.progress || (r.state === 'RUNNING' ? 'starting' : 'waiting for a worker'))}</span>
      <span class="jage">${esc(ago(r.lastModified))}</span>
    </div>`).join('');
}

/* Poll only while something is in flight, and stop when nothing is. A console
 * left open on an idle server should not talk to it forever. */
let runsTimer = null;
function scheduleRunsRefresh() {
  clearTimeout(runsTimer);
  if (!allRuns.some((r) => IN_FLIGHT.has(r.state))) return;
  runsTimer = setTimeout(() => loadRuns({ quiet: true }), 10000);
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

/* ------------------------------------------------------- kept releases */

let keptReleases = [];

/* Ask the server which kept release precedes this package. The rule - same
 * edition, newest effective time before it, status ignored - lives on the
 * server so the nightly and this page cannot disagree about it. */
async function detectPrevious(filename) {
  if (!filename) return '';
  try {
    const res = await fetch(`${API}/releases/previous?forFile=${encodeURIComponent(filename)}`,
      { credentials: 'same-origin' });
    if (res.status === 204) return '';        // nothing suitable kept
    if (!res.ok) return '';
    return (await res.json()).previousRelease || '';
  } catch {
    return '';
  }
}

/* Show what auto-detect would choose, so the choice is visible before the run
 * rather than discovered afterwards in the report. */
async function previewPrevious() {
  const hint = $('#previousHint');
  const name = fileInput.files?.[0]?.name;
  if ($('#previousRelease').value !== '__auto__') {
    hint.textContent = 'Kept releases are loaded from the server.';
    return;
  }
  if (!name) { hint.textContent = 'Choose a package and the match will be shown here.'; return; }
  hint.textContent = 'checking\u2026';
  const found = await detectPrevious(name);
  hint.textContent = found
    ? `will use ${found}`
    : 'no earlier release of this edition is kept - it will run as a first-time release';
}

async function loadReleases() {
  loadReleases.done = true;
  const box = $('#releaseList');
  box.innerHTML = '<p class="muted">loading&hellip;</p>';
  try {
    keptReleases = await api('/releases');
    const select = $('#previousRelease');
    const chosen = select.value;
    select.innerHTML =
      '<option value="__auto__">Detect from the package I chose</option>'
      + '<option value="">None - first-time release</option>'
      + keptReleases.map((n) => `<option value="${esc(n)}">${esc(n)}</option>`).join('');
    select.value = chosen;

    box.innerHTML = keptReleases.length
      ? `<table class="runs"><tbody>${keptReleases.map((n) =>
          `<tr><td>${esc(n)}</td></tr>`).join('')}</tbody></table>`
      : '<p class="empty">No releases kept yet. A validation with no previous release runs as a first-time release.</p>';
  } catch (e) {
    box.innerHTML = `<p class="muted">Could not list releases: ${esc(e.message)}</p>`;
  }
}

const releaseInput = $('#releaseFile');
releaseInput.addEventListener('change', () => {
  const f = releaseInput.files?.[0];
  $('#releaseDropFile').textContent = f ? `${f.name} (${(f.size / 1048576).toFixed(1)} MB)` : '';
});

$('#releaseForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const file = releaseInput.files?.[0];
  if (!file) { toast('Choose a zip first.'); return; }

  const btn = $('#releaseBtn');
  btn.disabled = true;
  btn.textContent = 'Uploading\u2026';
  try {
    const body = new FormData();
    body.append('file', file);
    // The path segments are the MySQL schema-naming convention and are ignored
    // by the DuckDB catalogue, which keeps the package under its own filename.
    // They are still required by the route, so they are derived rather than
    // asked for.
    const version = (file.name.match(/(?<!\d)(\d{8})(?:T\d{6}Z)?(?!\d)/g) || ['00000000']).pop().slice(0, 8);
    await upload(`/releases/kept/${version}`, body,
      progressUI($('#releaseProgress'), 'starting\u2026'));
    $('#releaseProgress').hidden = true;
    toast(`Kept ${file.name}`, true);
    releaseInput.value = '';
    $('#releaseDropFile').textContent = '';
    await loadReleases();
    previewPrevious();
  } catch (err) {
    $('#releaseProgress').hidden = true;
    toast(`Could not keep that release: ${err.message}`);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Keep this release';
  }
});

$('#refreshReleases').addEventListener('click', loadReleases);
$('#previousRelease').addEventListener('change', previewPrevious);

/* ------------------------------------------------------------------- boot */

defaults();
loadVersion();
loadGroups();
loadReleases();
// Start-up: learn whether anything is in flight, so the tab badge is
// right without the reports tab having been opened.
loadRuns({ quiet: true });
// Last, so a deep link overrides the default panel and the generated run id
// that the calls above have just put on the form.
openFromLink();
