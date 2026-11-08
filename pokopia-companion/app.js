/* Pokopia Companion Guide: app logic. No build step, no dependencies. */
(function () {
  'use strict';

  const D = window.POKOPIA_DATA;
  const KEY = 'pokopia-companion-v1';

  /* ---------- Helpers ---------- */

  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ESC[c]);
  const slug = (s) => String(s).toLowerCase().normalize('NFKD')
    .replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  const pad3 = (n) => String(n).padStart(3, '0');
  const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const $ = (sel, root) => (root || document).querySelector(sel);
  const cssId = (s) => (window.CSS && CSS.escape ? CSS.escape(s) : String(s));

  const specById = Object.fromEntries(D.specialties.map((s) => [s.id, s]));
  const areaById = Object.fromEntries(D.areas.map((a) => [a.id, a]));
  const storyAreas = D.areas.filter((a) => a.step > 0).sort((a, b) => a.step - b.step);

  /* ---------- Saved progress (this browser only) ---------- */

  function freshState() {
    return {
      v: 1,
      dex: {},            // pokemon id -> 'seen' | 'friend'
      customPokemon: [],
      habitats: {},       // habitat id -> copies built
      customHabitats: [],
      moves: {},          // move id -> true
      areas: {},          // area id -> 'progress' | 'restored'
      areaNotes: {},
      goals: {},          // 'areaId:index' -> true
      requests: D.starterRequests.map((r, i) => ({
        id: 'starter-' + i, text: r.text, who: r.who, area: r.area, done: false, suggested: true
      })),
      notes: '',
      tip: 0
    };
  }

  function normalize(obj) {
    const base = freshState();
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return base;
    const out = Object.assign(base, obj);
    ['dex', 'habitats', 'moves', 'areas', 'areaNotes', 'goals'].forEach((k) => {
      if (!out[k] || typeof out[k] !== 'object' || Array.isArray(out[k])) out[k] = {};
    });
    ['customPokemon', 'customHabitats', 'requests'].forEach((k) => {
      if (!Array.isArray(out[k])) out[k] = [];
    });
    if (typeof out.notes !== 'string') out.notes = '';
    if (typeof out.tip !== 'number' || !isFinite(out.tip)) out.tip = 0;
    return out;
  }

  function loadState() {
    try {
      const raw = localStorage.getItem(KEY);
      return raw ? normalize(JSON.parse(raw)) : freshState();
    } catch (e) {
      return freshState();
    }
  }

  let S = loadState();
  let saveTimer = null;

  function save() {
    clearTimeout(saveTimer);
    try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) { /* storage unavailable */ }
    updateChrome();
  }
  function saveSoon() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(save, 300);
  }

  /* ---------- Derived data ---------- */

  function allPokemon() {
    const seeded = D.pokemon.map((p) => Object.assign({ id: slug(p.name) }, p));
    const mine = S.customPokemon.map((p) => Object.assign({ custom: true }, p));
    return seeded.concat(mine).sort((a, b) => {
      const ad = a.dex == null ? Infinity : a.dex;
      const bd = b.dex == null ? Infinity : b.dex;
      return ad === bd ? 0 : ad - bd;
    });
  }

  function allHabitats() {
    const mine = S.customHabitats.map((h) => Object.assign({ custom: true }, h));
    return D.habitats.concat(mine).sort((a, b) => {
      const an = a.no == null ? Infinity : a.no;
      const bn = b.no == null ? Infinity : b.no;
      return an === bn ? 0 : an - bn;
    });
  }

  function progress() {
    const mons = allPokemon();
    const habs = allHabitats();
    const friends = mons.filter((p) => S.dex[p.id] === 'friend').length;
    const built = habs.filter((h) => (S.habitats[h.id] || 0) > 0).length;
    const moves = D.moves.filter((m) => S.moves[m.id]).length;
    const restored = storyAreas.filter((a) => S.areas[a.id] === 'restored').length;
    const parts = [
      friends / mons.length,
      habs.length ? built / habs.length : 0,
      moves / D.moves.length,
      restored / storyAreas.length
    ];
    const pct = Math.round((parts.reduce((a, b) => a + b, 0) / parts.length) * 100);
    return {
      pct, friends, mons: mons.length, built, habs: habs.length,
      moves, movesTotal: D.moves.length, restored, areasTotal: storyAreas.length
    };
  }

  function tileClass(material) {
    const s = String(material).toLowerCase();
    if (/ocean|sea/.test(s)) return 't-ocean';
    if (/water|pond|duckweed|river/.test(s)) return 't-water';
    if (/tree|shrub|bush/.test(s)) return 't-tree';
    if (/flower|blossom|petal/.test(s)) return 't-flower';
    if (/grass|hedge|moss|leaf|vine/.test(s)) return 't-grass';
    if (/fire|torch|lamp|lantern|ember/.test(s)) return 't-fire';
    if (/rock|stone|boulder|iron|metal|ore/.test(s)) return 't-rock';
    if (/sand|soil|dirt|mud/.test(s)) return 't-soil';
    return 't-wood';
  }

  const verifyBadge = (item) => (item.verify ? '<span class="check">Check in-game</span>' : '');
  const noteLine = (item) => (item.note && item.verify
    ? `<p class="note"><b>Check in-game:</b> ${esc(item.note)}</p>`
    : item.note ? `<p class="note">${esc(item.note)}</p>` : '');

  /* Two-tap confirmation for anything that deletes data. */
  function confirmTap(el, armedText) {
    if (el.dataset.armed === '1') return true;
    el.dataset.armed = '1';
    el.dataset.label = el.textContent;
    el.textContent = armedText;
    el.classList.add('is-armed');
    setTimeout(() => {
      if (el.isConnected && el.dataset.armed === '1') {
        el.dataset.armed = '';
        el.textContent = el.dataset.label;
        el.classList.remove('is-armed');
      }
    }, 4000);
    return false;
  }

  function refocus(act, id) {
    const el = document.querySelector(`[data-act="${act}"][data-id="${cssId(id)}"]`);
    if (el) el.focus();
  }

  function flash(id, text) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = text;
    clearTimeout(el._t);
    el._t = setTimeout(() => { el.textContent = ''; }, 2500);
  }

  function setPressed(el) {
    el.parentElement.querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(b === el)));
  }

  /* UI-only state (filters). Not saved. */
  const UI = { dexQ: '', dexSpec: '', dexStatus: 'all', habQ: '', habShow: 'all', reqShow: 'open' };

  /* ---------- Header ---------- */

  const VIEWS = {
    home: { label: 'Home', render: renderHome },
    dex: { label: 'Pokédex', render: renderDex },
    habitats: { label: 'Habitats', render: renderHabitats },
    moves: { label: 'Ditto’s moves', render: renderMoves },
    specialties: { label: 'Specialties', render: renderSpecialties },
    areas: { label: 'Areas', render: renderAreas },
    kitchen: { label: 'Cooking & crafting', render: renderKitchen },
    journal: { label: 'Journal', render: renderJournal }
  };

  const main = $('#view');
  let current = null;

  function renderTabs() {
    $('#tabs').innerHTML = Object.keys(VIEWS)
      .map((k) => `<a class="tab" href="#${k}" data-view="${k}">${esc(VIEWS[k].label)}</a>`)
      .join('');
  }

  function updateChrome() {
    const chip = $('#restore-chip');
    if (chip) {
      const pct = progress().pct;
      chip.innerHTML = `<span class="dot" aria-hidden="true"></span>${pct}% restored`;
      chip.setAttribute('aria-label', `${pct} percent restored. Go to Home.`);
    }
  }

  function route() {
    const name = location.hash.replace(/^#/, '');
    const next = VIEWS[name] ? name : 'home';
    const changed = next !== current;
    current = next;
    document.querySelectorAll('.tab').forEach((t) => {
      if (t.dataset.view === next) t.setAttribute('aria-current', 'page');
      else t.removeAttribute('aria-current');
    });
    VIEWS[next].render(main);
    if (changed) {
      window.scrollTo(0, 0);
      const t = $('.tab[aria-current]');
      if (t && t.scrollIntoView) t.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }
  }

  function go(name) {
    if (location.hash === '#' + name) route();
    else location.hash = name;
  }

  /* ---------- Home ---------- */

  // 12 x 4 restoration map. g grass, t tree, f flower, w water.
  const ISLAND = 'ggtgggfggtgg' + 'gfggggwwgggt' + 'tgggfwwwggfg' + 'ggtgggwgggtg';
  const ISLAND_RANK = (() => {
    const n = ISLAND.length;
    const order = Array.from({ length: n }, (_, i) => i);
    let seed = 11;
    const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    for (let i = n - 1; i > 0; i--) {
      const j = Math.floor(rnd() * (i + 1));
      [order[i], order[j]] = [order[j], order[i]];
    }
    const rank = new Array(n);
    order.forEach((tile, pos) => { rank[tile] = pos; });
    return rank;
  })();

  function islandTiles(pct) {
    const n = ISLAND.length;
    const green = pct > 0 ? Math.max(1, Math.round((pct / 100) * n)) : 0;
    const restoredCls = { g: 't-grass', t: 't-tree', f: 't-flower', w: 't-water' };
    let out = '';
    for (let i = 0; i < n; i++) {
      let cls;
      if (ISLAND_RANK[i] < green) cls = restoredCls[ISLAND[i]];
      else if (i % 11 === 3) cls = 't-rock';
      else cls = (i * 7) % 3 === 0 ? 't-soil-2' : 't-soil';
      out += `<span class="tile ${cls}"></span>`;
    }
    return out;
  }

  function progressRow(view, label, n, total) {
    const pct = total ? Math.round((n / total) * 100) : 0;
    return `<a class="progress-row" href="#${view}">
      <span class="label">${label}</span>
      <span class="bar" aria-hidden="true"><span style="width:${pct}%"></span></span>
      <span class="num">${n} / ${total}</span>
    </a>`;
  }

  function nextUp() {
    const items = [];
    S.requests.filter((r) => !r.done).slice(0, 3).forEach((r) => {
      items.push({
        kind: 'Request',
        href: '#journal',
        html: esc(r.text) + (r.who ? ` <span class="muted small">for ${esc(r.who)}</span>` : '')
      });
    });
    const m = D.moves.find((mv) => !S.moves[mv.id]);
    if (m) {
      items.push({
        kind: 'Move',
        href: '#moves',
        html: `Learn <strong>${esc(m.name)}</strong> from ${esc(m.from)}${m.area ? ' in ' + esc(areaById[m.area].name) : ''}`
      });
    }
    const h = allHabitats().find((hb) => !((S.habitats[hb.id] || 0) > 0));
    if (h) {
      items.push({
        kind: 'Build',
        href: '#habitats',
        html: `<strong>${esc(h.name)}</strong>: ${h.req.map(([mat, c]) => esc(mat) + ' ×' + c).join(', ')}`
      });
    }
    const a = storyAreas.find((ar) => S.areas[ar.id] !== 'restored');
    if (a) items.push({ kind: 'Area', href: '#areas', html: `Restore <strong>${esc(a.name)}</strong>` });

    if (!items.length) {
      return '<p class="muted">You have finished everything this guide tracks. Add your own goals in the Journal.</p>';
    }
    return `<ul class="next-list">${items
      .map((i) => `<li><span class="kind">${i.kind}</span><a href="${i.href}">${i.html}</a></li>`)
      .join('')}</ul>`;
  }

  function renderHome(root) {
    const P = progress();
    const tipIndex = S.tip % D.tips.length;
    const headline = P.pct === 0
      ? 'Your world is still withered'
      : P.pct >= 100 ? 'Everything is green again' : `${P.pct}% of your world restored`;
    root.innerHTML = `
      <section class="hero">
        <div class="hero-text">
          <p class="eyebrow">Your restoration</p>
          <h1>${headline}</h1>
          <p class="lead">Every Pokémon you befriend, habitat you build, move you learn and area you restore turns another patch of this map green.</p>
          <p class="facts">
            <span><strong>${esc(D.meta.game)}</strong></span>
            <span>${esc(D.meta.platform)}</span>
            <span>Released ${esc(D.meta.released)}</span>
          </p>
        </div>
        <div class="island" role="img" aria-label="Restoration map, ${P.pct} percent green">${islandTiles(P.pct)}</div>
      </section>

      <section class="panel" aria-labelledby="prog-h">
        <div class="section-title">
          <h2 id="prog-h">Progress</h2>
          <span class="count-line">Counts cover the entries in this guide</span>
        </div>
        <div class="progress-list">
          ${progressRow('dex', 'Pokémon befriended', P.friends, P.mons)}
          ${progressRow('habitats', 'Habitats built', P.built, P.habs)}
          ${progressRow('moves', 'Moves learned', P.moves, P.movesTotal)}
          ${progressRow('areas', 'Areas restored', P.restored, P.areasTotal)}
        </div>
      </section>

      <section class="two-col">
        <div class="panel" aria-labelledby="next-h">
          <h2 id="next-h">Next up</h2>
          ${nextUp()}
        </div>
        <div class="panel tip-card">
          <p class="eyebrow">Field tip ${tipIndex + 1} of ${D.tips.length}</p>
          <p class="tip">${esc(D.tips[tipIndex])}</p>
          <div><button class="btn" type="button" data-act="next-tip">Show another tip</button></div>
        </div>
      </section>`;
  }

  /* ---------- Pokédex ---------- */

  const STATUS_LABEL = { none: 'Not met yet', seen: 'Seen', friend: 'Befriended' };
  const STATUS_NEXT = { none: 'seen', seen: 'friend', friend: 'none' };

  function specOptions(selected) {
    return D.specialties
      .map((s) => `<option value="${s.id}"${selected === s.id ? ' selected' : ''}>${esc(s.name)}</option>`)
      .join('');
  }

  function renderDex(root) {
    const statuses = [['all', 'All'], ['none', 'Not met'], ['seen', 'Seen'], ['friend', 'Befriended']];
    root.innerHTML = `
      <header class="view-head">
        <p class="eyebrow">Pokédex</p>
        <h1>Who have you met?</h1>
        <p class="lead">Tap a status to move a Pokémon from Not met yet, to Seen, to Befriended. The game has ${esc(D.meta.pokedexTotal)} Pokémon. This list starts with the ones community guides have documented, and you can add the rest as you find them.</p>
      </header>
      <div class="toolbar">
        <input type="search" id="dex-q" data-input="dex-q" placeholder="Search name, specialty or habitat" value="${esc(UI.dexQ)}" aria-label="Search Pokémon">
        <select id="dex-spec" data-change="dex-spec" aria-label="Filter by specialty">
          <option value="">All specialties</option>${specOptions(UI.dexSpec)}
        </select>
        <div class="seg" role="group" aria-label="Filter by status">
          ${statuses.map(([v, l]) => `<button type="button" data-act="dex-status" data-v="${v}" aria-pressed="${UI.dexStatus === v}">${l}</button>`).join('')}
        </div>
      </div>
      <p class="count-line" id="dex-count" aria-live="polite"></p>
      <div class="grid" id="dex-list"></div>
      <details class="add">
        <summary>Add a Pokémon you found</summary>
        <form data-form="add-mon" autocomplete="off">
          <label class="field">Name<input type="text" id="add-mon-name" name="name" required maxlength="40"></label>
          <label class="field">Pokédex number <span class="hint">optional</span><input type="number" id="add-mon-dex" name="dex" min="1" max="1100" inputmode="numeric"></label>
          <label class="field">Specialty<select id="add-mon-spec" name="spec"><option value="">Unknown</option>${specOptions('')}</select></label>
          <label class="field">Where you found it<input type="text" id="add-mon-where" name="where" maxlength="80"></label>
          <label class="field full">Notes<input type="text" id="add-mon-note" name="note" maxlength="140"></label>
          <div class="form-row full">
            <button class="btn btn-primary" type="submit">Add to Pokédex</button>
            <span class="status-msg" id="add-mon-msg" role="status"></span>
          </div>
        </form>
      </details>`;
    updateDex();
  }

  function dexMatches(p) {
    if (UI.dexSpec && !(p.specs || []).includes(UI.dexSpec)) return false;
    const st = S.dex[p.id] || 'none';
    if (UI.dexStatus !== 'all' && st !== UI.dexStatus) return false;
    const q = UI.dexQ.trim().toLowerCase();
    if (!q) return true;
    const hay = [
      p.name, p.where, p.role, p.note, p.dex ? pad3(p.dex) : '',
      ...(p.specs || []).map((id) => (specById[id] ? specById[id].name : id))
    ].join(' ').toLowerCase();
    return hay.includes(q);
  }

  function monCard(p) {
    const st = S.dex[p.id] || 'none';
    const specs = (p.specs || []).filter((id) => specById[id]);
    return `<article class="card${st === 'friend' ? ' is-friend' : ''}">
      <div class="card-top">
        <span class="dexno">${p.dex ? '#' + pad3(p.dex) : 'Pokopia original'}</span>
        ${verifyBadge(p)}${p.custom ? '<span class="tag">Added by you</span>' : ''}
      </div>
      <h3>${esc(p.name)}</h3>
      ${specs.length ? `<div class="pills">${specs.map((id) => `<button type="button" class="pill" data-act="goto-spec" data-id="${id}" title="Show all ${esc(specById[id].name)} Pokémon">${esc(specById[id].name)}</button>`).join('')}</div>` : ''}
      ${p.where ? `<p class="meta"><strong>Found:</strong> ${esc(p.where)}</p>` : ''}
      ${p.role ? `<p class="meta">${esc(p.role)}</p>` : ''}
      ${p.note ? `<p class="meta">${esc(p.note)}</p>` : ''}
      <div class="foot">
        <button type="button" class="status s-${st}" data-act="cycle-mon" data-id="${esc(p.id)}" aria-label="${esc(p.name)}: ${STATUS_LABEL[st]}. Tap to change.">${STATUS_LABEL[st]}</button>
        ${p.custom ? `<button type="button" class="btn btn-ghost" data-act="del-mon" data-id="${esc(p.id)}">Remove</button>` : ''}
      </div>
    </article>`;
  }

  function updateDex() {
    const list = $('#dex-list');
    if (!list) return;
    const all = allPokemon();
    const shown = all.filter(dexMatches);
    const friends = all.filter((p) => S.dex[p.id] === 'friend').length;
    const seen = all.filter((p) => S.dex[p.id] === 'seen').length;
    $('#dex-count').textContent = `Showing ${shown.length} of ${all.length} · ${friends} befriended · ${seen} seen`;
    list.innerHTML = shown.length
      ? shown.map(monCard).join('')
      : `<div class="empty">No Pokémon match these filters. <button type="button" class="btn" data-act="clear-dex">Clear filters</button></div>`;
  }

  /* ---------- Habitats ---------- */

  function renderHabitats(root) {
    const shows = [['all', 'All'], ['todo', 'Not built'], ['built', 'Built']];
    root.innerHTML = `
      <header class="view-head">
        <p class="eyebrow">Habitat Dex</p>
        <h1>Habitats to build</h1>
        <p class="lead">A habitat is a small recipe of blocks and items. Build it and Pokémon move in. The full Habitat Dex has ${D.meta.habitatTotal} entries; this list covers the confirmed early builds. Count every copy you build, since extra copies raise your odds of finding its Pokémon.</p>
      </header>
      <div class="toolbar">
        <input type="search" id="hab-q" data-input="hab-q" placeholder="Search habitat, item or Pokémon" value="${esc(UI.habQ)}" aria-label="Search habitats">
        <div class="seg" role="group" aria-label="Filter by built status">
          ${shows.map(([v, l]) => `<button type="button" data-act="hab-show" data-v="${v}" aria-pressed="${UI.habShow === v}">${l}</button>`).join('')}
        </div>
      </div>
      <p class="count-line" id="hab-count" aria-live="polite"></p>
      <div class="grid wide" id="hab-list"></div>
      <details class="add">
        <summary>Add a habitat from your Habitat Dex</summary>
        <form data-form="add-hab" autocomplete="off">
          <label class="field">Habitat name<input type="text" id="add-hab-name" name="name" required maxlength="50"></label>
          <label class="field">Habitat Dex number <span class="hint">optional</span><input type="number" id="add-hab-no" name="no" min="1" max="999" inputmode="numeric"></label>
          <label class="field full">What it needs <span class="hint">one item per line, like “Tall Grass x4”</span>
            <textarea id="add-hab-req" name="req" rows="3" required></textarea></label>
          <label class="field">Pokémon that live there <span class="hint">separate with commas</span><input type="text" id="add-hab-res" name="residents" maxlength="200"></label>
          <label class="field">Conditions <span class="hint">time, weather, area</span><input type="text" id="add-hab-cond" name="cond" maxlength="140"></label>
          <div class="form-row full">
            <button class="btn btn-primary" type="submit">Add habitat</button>
            <span class="status-msg" id="add-hab-msg" role="status"></span>
          </div>
        </form>
      </details>`;
    updateHabs();
  }

  function habCard(h) {
    const n = S.habitats[h.id] || 0;
    const residents = h.residents || [];
    return `<article class="card${n > 0 ? ' is-built' : ''}">
      <div class="card-top">
        <span class="dexno">${h.no ? 'No. ' + pad3(h.no) : 'Habitat'}</span>
        ${verifyBadge(h)}${h.custom ? '<span class="tag">Added by you</span>' : ''}
      </div>
      <h3>${esc(h.name)}</h3>
      <ul class="recipe" aria-label="Needs">
        ${h.req.map(([m, c]) => `<li><span class="tile ${tileClass(m)}" aria-hidden="true"></span>${esc(m)} <b>×${esc(c)}</b></li>`).join('')}
      </ul>
      ${residents.length
        ? `<p class="meta"><strong>Attracts</strong></p><div class="pills">${residents.map((r) => `<button type="button" class="pill" data-act="goto-mon" data-name="${esc(r)}">${esc(r)}</button>`).join('')}</div>`
        : '<p class="meta">Residents not confirmed yet.</p>'}
      ${h.cond ? `<p class="meta">${esc(h.cond)}</p>` : ''}
      ${noteLine(h)}
      <div class="foot">
        <span class="built-label">${n > 0 ? (n === 1 ? 'Built once' : `Built ${n} times`) : 'Not built yet'}</span>
        <div class="counter">
          <button type="button" data-act="hab-dec" data-id="${esc(h.id)}" aria-label="One fewer ${esc(h.name)}">−</button>
          <output aria-live="polite">${n}</output>
          <button type="button" data-act="hab-inc" data-id="${esc(h.id)}" aria-label="One more ${esc(h.name)}">+</button>
        </div>
      </div>
      ${h.custom ? `<div><button type="button" class="btn btn-ghost" data-act="del-hab" data-id="${esc(h.id)}">Remove habitat</button></div>` : ''}
    </article>`;
  }

  function updateHabs() {
    const list = $('#hab-list');
    if (!list) return;
    const all = allHabitats();
    const q = UI.habQ.trim().toLowerCase();
    const shown = all.filter((h) => {
      const n = S.habitats[h.id] || 0;
      if (UI.habShow === 'built' && n === 0) return false;
      if (UI.habShow === 'todo' && n > 0) return false;
      if (!q) return true;
      const hay = [h.name, h.cond, h.no ? pad3(h.no) : '', ...h.req.map((r) => r[0]), ...(h.residents || [])]
        .join(' ').toLowerCase();
      return hay.includes(q);
    });
    const built = all.filter((h) => (S.habitats[h.id] || 0) > 0).length;
    $('#hab-count').textContent = `Showing ${shown.length} of ${all.length} · ${built} built`;
    list.innerHTML = shown.length
      ? shown.map(habCard).join('')
      : '<div class="empty">No habitats match. Try another search, or add one below.</div>';
  }

  function parseReq(text) {
    return String(text).split(/[\n,;]+/).map((s) => s.trim()).filter(Boolean).map((s) => {
      let m = s.match(/^(.*?)\s*[x×*]\s*(\d+)$/i);
      if (m && m[1]) return [m[1].trim(), parseInt(m[2], 10)];
      m = s.match(/^(\d+)\s*[x×*]?\s*(.+)$/i);
      if (m) return [m[2].trim(), parseInt(m[1], 10)];
      return [s, 1];
    });
  }

  /* ---------- Moves ---------- */

  function renderMoves(root) {
    const learned = D.moves.filter((m) => S.moves[m.id]).length;
    root.innerHTML = `
      <header class="view-head">
        <p class="eyebrow">Ditto’s moves</p>
        <h1>Moves to copy</h1>
        <p class="lead">You play as a Ditto that learns moves by copying the Pokémon you help. Moves use PP. Rest at a Pokémon Center or eat a meal to refill it.</p>
      </header>
      <div class="panel">
        <div class="section-title">
          <h2>Learned</h2>
          <span class="mono" id="moves-count">${learned} of ${D.moves.length}</span>
        </div>
        <p class="small muted">Guides count 13 or 14 moves in all, including a few Ditto knows from the start. This list covers the moves other Pokémon teach you, roughly in story order. Four of them can be powered up by eating the matching meal.</p>
      </div>
      <ol class="move-list">
        ${D.moves.map((m) => {
          const on = !!S.moves[m.id];
          const area = m.area && areaById[m.area] ? areaById[m.area].name : '';
          return `<li class="move${on ? ' is-learned' : ''}">
            <label class="learn"><input type="checkbox" id="mv-${m.id}" data-change="move" data-id="${m.id}"${on ? ' checked' : ''} aria-label="Learned ${esc(m.name)}"></label>
            <div class="move-body">
              <div class="move-title"><h3>${esc(m.name)}</h3>${verifyBadge(m)}</div>
              <p class="meta">From <button type="button" class="pill" data-act="goto-mon" data-name="${esc(m.from)}">${esc(m.from)}</button>${area ? ' · ' + esc(area) : ''}</p>
              <p>${esc(m.use)}</p>
              ${noteLine(m)}
            </div>
            <div class="move-meal">${m.meal ? `<span class="k">Power-up</span><span>${esc(m.meal)}</span>` : ''}</div>
          </li>`;
        }).join('')}
      </ol>`;
  }

  /* ---------- Specialties ---------- */

  function renderSpecialties(root) {
    const mons = allPokemon();
    root.innerHTML = `
      <header class="view-head">
        <p class="eyebrow">Specialties</p>
        <h1>What each Pokémon is good at</h1>
        <p class="lead">Every resident has a specialty that helps your town, from growing plants to powering machines. Invite the Pokémon whose specialties match what you are building. Guides list up to 31 specialties; these are the ones you will run into most.</p>
      </header>
      <div class="grid wide">
        ${D.specialties.map((s) => {
          const names = Array.from(new Set(s.examples.concat(
            mons.filter((p) => (p.specs || []).includes(s.id)).map((p) => p.name)
          )));
          return `<article class="card">
            <div class="card-top"><h3>${esc(s.name)}</h3>${verifyBadge(s)}</div>
            <p>${esc(s.does)}</p>
            ${names.length
              ? `<div class="pills">${names.map((n) => `<button type="button" class="pill" data-act="goto-mon" data-name="${esc(n)}">${esc(n)}</button>`).join('')}</div>`
              : '<p class="meta">No confirmed examples yet. Add them in the Pokédex when you find one.</p>'}
            ${names.length ? `<div class="foot"><button type="button" class="btn" data-act="goto-spec" data-id="${s.id}">Show in Pokédex</button></div>` : ''}
          </article>`;
        }).join('')}
      </div>`;
  }

  /* ---------- Areas ---------- */

  const AREA_STATUS = [['', 'Not started'], ['progress', 'In progress'], ['restored', 'Restored']];

  function areaCard(a) {
    const st = S.areas[a.id] || '';
    const cls = st === 'restored' ? ' is-restored' : st === 'progress' ? ' is-progress' : '';
    return `<article class="card area-card${cls}" data-area="${a.id}">
      <div class="card-top">
        <span class="step-no" aria-label="${a.step ? 'Step ' + a.step : 'Side area'}">${a.step ? a.step : '+'}</span>
        ${verifyBadge(a)}
      </div>
      <h3>${esc(a.name)}</h3>
      ${a.inspired ? `<p class="meta">Based on ${esc(a.inspired)}</p>` : ''}
      <p>${esc(a.summary)}</p>
      ${a.goals.length ? `<ul class="area-goals">${a.goals.map((g, i) => {
        const key = a.id + ':' + i;
        return `<li><label class="goal"><input type="checkbox" id="goal-${a.id}-${i}" data-change="goal" data-id="${key}"${S.goals[key] ? ' checked' : ''}><span>${esc(g)}</span></label></li>`;
      }).join('')}</ul>` : ''}
      ${a.unlocks ? `<p class="meta"><strong>Opens:</strong> ${esc(a.unlocks)}</p>` : ''}
      ${noteLine(a)}
      <label class="field">Status
        <select id="area-${a.id}" data-change="area" data-id="${a.id}">
          ${AREA_STATUS.map(([v, l]) => `<option value="${v}"${st === v ? ' selected' : ''}>${l}</option>`).join('')}
        </select>
      </label>
      <label class="field">Your notes
        <textarea id="area-note-${a.id}" data-input="area-note" data-id="${a.id}" rows="2" placeholder="Where you left off, what to build next">${esc(S.areaNotes[a.id] || '')}</textarea>
      </label>
    </article>`;
  }

  function renderAreas(root) {
    const byStep = (n) => D.areas.filter((a) => a.step === n);
    root.innerHTML = `
      <header class="view-head">
        <p class="eyebrow">Areas</p>
        <h1>Your route through the story</h1>
        <p class="lead">The story starts in one area and branches into two. Finish both branches to open the last one. Tick off goals and mark each area as you restore it.</p>
      </header>
      <div class="route">
        <div class="route-row">${byStep(1).map(areaCard).join('')}</div>
        <div class="route-link">Throw a Rain Dance party to open both</div>
        <div class="route-row">${byStep(2).map(areaCard).join('')}</div>
        <div class="route-link">Finish both main stories</div>
        <div class="route-row">${byStep(3).map(areaCard).join('')}</div>
        <div class="route-link">After the credits</div>
        <div class="route-row">${byStep(4).map(areaCard).join('')}</div>
      </div>
      <section class="view-head">
        <h2>Side area</h2>
      </section>
      <div class="route-row">${byStep(0).map(areaCard).join('')}</div>`;
  }

  /* ---------- Cooking & crafting ---------- */

  function renderKitchen(root) {
    root.innerHTML = `
      <header class="view-head">
        <p class="eyebrow">Cooking & crafting</p>
        <h1>Meals, tools and workbenches</h1>
        <p class="lead">Meals refill your PP and power up Ditto’s four core moves. Everything else you build starts at the workbench.</p>
      </header>
      <section class="panel" aria-labelledby="meals-h">
        <div class="section-title"><h2 id="meals-h">Meals that power up Ditto</h2>${verifyBadge({ verify: true })}</div>
        <div class="table-scroll">
          <table class="meals">
            <thead><tr><th scope="col">Meal</th><th scope="col">First recipe</th><th scope="col">Tool</th><th scope="col">Heat source</th><th scope="col">Powers up</th></tr></thead>
            <tbody>
              ${D.meals.map((m) => `<tr>
                <td><strong>${esc(m.meal)}</strong></td>
                <td>${esc(m.starter)}</td>
                <td>${esc(m.tool)}</td>
                <td>${m.heat ? 'Needed' : 'Not needed'}</td>
                <td class="move-cell">${esc(m.move)}</td>
              </tr>`).join('')}
            </tbody>
          </table>
        </div>
        <p class="note"><b>Check in-game:</b> ${esc(D.powerUpNote)}</p>
      </section>
      <div class="grid wide">
        <section class="card">
          <div class="card-top"><h3>Unlocking cooking</h3>${verifyBadge(D.cookingUnlock)}</div>
          <p class="small">${esc(D.cookingUnlock.note)}</p>
        </section>
        <section class="card">
          <h3>Cooking tips</h3>
          <ul class="plain small">${D.cookingNotes.map((t) => `<li>${esc(t)}</li>`).join('')}</ul>
        </section>
        <section class="card">
          <h3>Crafting basics</h3>
          <ul class="plain small">${D.crafting.map((t) => `<li>${esc(t)}</li>`).join('')}</ul>
        </section>
        <section class="card">
          <h3>Playing together</h3>
          <ul class="plain small">${D.multiplayer.map((t) => `<li>${esc(t)}</li>`).join('')}</ul>
        </section>
      </div>`;
  }

  /* ---------- Journal ---------- */

  function renderJournal(root) {
    const shows = [['open', 'To do'], ['done', 'Done'], ['all', 'All']];
    root.innerHTML = `
      <header class="view-head">
        <p class="eyebrow">Journal</p>
        <h1>Requests and notes</h1>
        <p class="lead">Keep track of what Pokémon ask you for, and jot down anything you want to remember for your next session.</p>
      </header>

      <section class="panel" aria-labelledby="req-h">
        <div class="section-title">
          <h2 id="req-h">Requests</h2>
          <div class="seg" role="group" aria-label="Show requests">
            ${shows.map(([v, l]) => `<button type="button" data-act="req-show" data-v="${v}" aria-pressed="${UI.reqShow === v}">${l}</button>`).join('')}
          </div>
        </div>
        <form class="req-form" data-form="add-req" autocomplete="off">
          <label class="field">Request<input type="text" id="req-text" name="text" required maxlength="160" placeholder="Bring Toy Blocks to Zorua"></label>
          <label class="field">From <span class="hint">optional</span><input type="text" id="req-who" name="who" list="mon-names" maxlength="40"></label>
          <label class="field">Area <span class="hint">optional</span>
            <select id="req-area" name="area"><option value="">Any</option>${D.areas.map((a) => `<option value="${a.id}">${esc(a.name)}</option>`).join('')}</select>
          </label>
          <button class="btn btn-primary" type="submit">Add</button>
        </form>
        <datalist id="mon-names">${allPokemon().map((p) => `<option value="${esc(p.name)}"></option>`).join('')}</datalist>
        <ul class="req-list" id="req-list"></ul>
      </section>

      <div class="two-col">
        <section class="panel" aria-labelledby="notes-h">
          <div class="section-title"><h2 id="notes-h">Notes</h2><span class="status-msg" id="notes-msg" role="status"></span></div>
          <textarea id="notes" data-input="notes" rows="9" placeholder="Habitat layouts, where you saw a rare Pokémon, what to cook next…">${esc(S.notes)}</textarea>
        </section>

        <section class="panel" aria-labelledby="backup-h">
          <h2 id="backup-h">Save code</h2>
          <p class="small muted">Your progress is saved in this browser only. Copy the save code to move it to another phone or computer, then paste it there.</p>
          <div class="form-row">
            <button type="button" class="btn" data-act="export">Copy save code</button>
            <span class="status-msg" id="backup-msg" role="status"></span>
          </div>
          <label class="field">Save code
            <textarea id="save-code" rows="3" spellcheck="false" placeholder="Paste a save code here to load it"></textarea>
          </label>
          <div class="form-row">
            <button type="button" class="btn" data-act="import">Load save code</button>
            <button type="button" class="btn btn-danger" data-act="reset">Erase all progress</button>
          </div>
        </section>
      </div>`;
    updateReqs();
  }

  function updateReqs() {
    const list = $('#req-list');
    if (!list) return;
    const shown = S.requests.filter((r) =>
      UI.reqShow === 'all' ? true : UI.reqShow === 'done' ? r.done : !r.done);
    if (!shown.length) {
      list.innerHTML = `<li class="empty">${UI.reqShow === 'done' ? 'Nothing finished yet.' : 'No open requests. Add one above when a Pokémon asks you for something.'}</li>`;
      return;
    }
    list.innerHTML = shown.map((r) => {
      const meta = [r.who ? 'For ' + esc(r.who) : '', r.area && areaById[r.area] ? esc(areaById[r.area].name) : '']
        .filter(Boolean).join(' · ');
      return `<li class="${r.done ? 'is-done' : ''}">
        <label><input type="checkbox" id="req-${esc(r.id)}" data-change="req-done" data-id="${esc(r.id)}"${r.done ? ' checked' : ''} aria-label="Done: ${esc(r.text)}"></label>
        <div class="req-text">
          <span>${esc(r.text)}</span>
          ${meta || r.suggested ? `<span class="meta">${meta}${r.suggested ? `${meta ? ' ' : ''}<span class="tag">Suggested</span>` : ''}</span>` : ''}
        </div>
        <button type="button" class="btn btn-ghost" data-act="del-req" data-id="${esc(r.id)}">Remove</button>
      </li>`;
    }).join('');
  }

  /* ---------- Events ---------- */

  const ACTIONS = {
    'next-tip'() {
      S.tip = (S.tip + 1) % D.tips.length;
      save();
      renderHome(main);
      const b = $('[data-act="next-tip"]');
      if (b) b.focus();
    },
    'dex-status'(el) { UI.dexStatus = el.dataset.v; setPressed(el); updateDex(); },
    'clear-dex'() {
      UI.dexQ = ''; UI.dexSpec = ''; UI.dexStatus = 'all';
      renderDex(main);
    },
    'cycle-mon'(el) {
      const id = el.dataset.id;
      const next = STATUS_NEXT[S.dex[id] || 'none'];
      if (next === 'none') delete S.dex[id]; else S.dex[id] = next;
      save();
      updateDex();
      refocus('cycle-mon', id);
    },
    'del-mon'(el) {
      if (!confirmTap(el, 'Tap again to remove')) return;
      const id = el.dataset.id;
      S.customPokemon = S.customPokemon.filter((p) => p.id !== id);
      delete S.dex[id];
      save();
      updateDex();
    },
    'goto-mon'(el) {
      UI.dexQ = el.dataset.name || ''; UI.dexSpec = ''; UI.dexStatus = 'all';
      go('dex');
    },
    'goto-spec'(el) {
      UI.dexSpec = el.dataset.id || ''; UI.dexQ = ''; UI.dexStatus = 'all';
      go('dex');
    },
    'hab-show'(el) { UI.habShow = el.dataset.v; setPressed(el); updateHabs(); },
    'hab-inc'(el) { bumpHabitat(el.dataset.id, 1); refocus('hab-inc', el.dataset.id); },
    'hab-dec'(el) { bumpHabitat(el.dataset.id, -1); refocus('hab-dec', el.dataset.id); },
    'del-hab'(el) {
      if (!confirmTap(el, 'Tap again to remove')) return;
      const id = el.dataset.id;
      S.customHabitats = S.customHabitats.filter((h) => h.id !== id);
      delete S.habitats[id];
      save();
      updateHabs();
    },
    'req-show'(el) { UI.reqShow = el.dataset.v; setPressed(el); updateReqs(); },
    'del-req'(el) {
      if (!confirmTap(el, 'Tap again')) return;
      S.requests = S.requests.filter((r) => r.id !== el.dataset.id);
      save();
      updateReqs();
    },
    'export'() {
      const box = $('#save-code');
      const code = JSON.stringify(S);
      box.value = code;
      const fallback = () => {
        box.focus();
        box.select();
        flash('backup-msg', 'Selected. Copy it with your device’s copy command.');
      };
      try {
        navigator.clipboard.writeText(code)
          .then(() => flash('backup-msg', 'Copied to clipboard'))
          .catch(fallback);
      } catch (e) { fallback(); }
    },
    'import'(el) {
      const box = $('#save-code');
      const text = box.value.trim();
      if (!text) { flash('backup-msg', 'Paste a save code into the box first.'); return; }
      let parsed;
      try { parsed = JSON.parse(text); } catch (e) {
        flash('backup-msg', 'That save code is incomplete. Copy the whole thing and try again.');
        return;
      }
      if (!parsed || typeof parsed !== 'object' || parsed.v !== 1) {
        flash('backup-msg', 'That is not a save code from this guide.');
        return;
      }
      if (!confirmTap(el, 'Tap again to replace your progress')) return;
      S = normalize(parsed);
      save();
      renderJournal(main);
      flash('backup-msg', 'Save code loaded');
    },
    'reset'(el) {
      if (!confirmTap(el, 'Tap again to erase everything')) return;
      S = freshState();
      save();
      renderJournal(main);
      flash('backup-msg', 'Progress erased');
    }
  };

  function bumpHabitat(id, delta) {
    const n = Math.max(0, Math.min(99, (S.habitats[id] || 0) + delta));
    if (n === 0) delete S.habitats[id]; else S.habitats[id] = n;
    save();
    updateHabs();
  }

  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-act]');
    if (!el) return;
    const fn = ACTIONS[el.dataset.act];
    if (!fn) return;
    e.preventDefault();
    fn(el);
  });

  document.addEventListener('input', (e) => {
    const el = e.target;
    const k = el.dataset && el.dataset.input;
    if (!k) return;
    if (k === 'dex-q') { UI.dexQ = el.value; updateDex(); }
    else if (k === 'hab-q') { UI.habQ = el.value; updateHabs(); }
    else if (k === 'notes') { S.notes = el.value; saveSoon(); flash('notes-msg', 'Saved'); }
    else if (k === 'area-note') { S.areaNotes[el.dataset.id] = el.value; saveSoon(); }
  });

  document.addEventListener('change', (e) => {
    const el = e.target;
    const k = el.dataset && el.dataset.change;
    if (!k) return;
    const id = el.dataset.id;
    if (k === 'dex-spec') {
      UI.dexSpec = el.value;
      updateDex();
    } else if (k === 'move') {
      if (el.checked) S.moves[id] = true; else delete S.moves[id];
      save();
      el.closest('.move').classList.toggle('is-learned', el.checked);
      const c = $('#moves-count');
      if (c) c.textContent = `${D.moves.filter((m) => S.moves[m.id]).length} of ${D.moves.length}`;
    } else if (k === 'area') {
      if (el.value) S.areas[id] = el.value; else delete S.areas[id];
      save();
      const card = el.closest('.area-card');
      card.classList.toggle('is-restored', el.value === 'restored');
      card.classList.toggle('is-progress', el.value === 'progress');
    } else if (k === 'goal') {
      if (el.checked) S.goals[id] = true; else delete S.goals[id];
      save();
    } else if (k === 'req-done') {
      const r = S.requests.find((x) => x.id === id);
      if (r) r.done = el.checked;
      save();
      updateReqs();
    }
  });

  document.addEventListener('submit', (e) => {
    const form = e.target;
    const k = form.dataset && form.dataset.form;
    if (!k) return;
    e.preventDefault();
    const f = new FormData(form);
    const val = (name) => String(f.get(name) || '').trim();

    if (k === 'add-mon') {
      const name = val('name');
      if (!name) return;
      const id = slug(name) || 'mon-' + uid();
      if (allPokemon().some((p) => p.id === id)) {
        flash('add-mon-msg', `${name} is already in the Pokédex.`);
        return;
      }
      const dex = parseInt(val('dex'), 10);
      S.customPokemon.push({
        id, name,
        dex: Number.isFinite(dex) && dex > 0 ? dex : null,
        specs: val('spec') ? [val('spec')] : [],
        where: val('where'),
        note: val('note')
      });
      save();
      form.reset();
      updateDex();
      flash('add-mon-msg', `Added ${name}`);
    } else if (k === 'add-hab') {
      const name = val('name');
      const req = parseReq(val('req'));
      if (!name) return;
      if (!req.length) { flash('add-hab-msg', 'List at least one item the habitat needs.'); return; }
      const no = parseInt(val('no'), 10);
      S.customHabitats.push({
        id: 'c-' + uid(),
        no: Number.isFinite(no) && no > 0 ? no : null,
        name, req,
        residents: val('residents').split(',').map((s) => s.trim()).filter(Boolean),
        cond: val('cond')
      });
      save();
      form.reset();
      updateHabs();
      flash('add-hab-msg', `Added ${name}`);
    } else if (k === 'add-req') {
      const text = val('text');
      if (!text) return;
      S.requests.unshift({ id: uid(), text, who: val('who'), area: val('area'), done: false });
      save();
      form.reset();
      if (UI.reqShow === 'done') {
        UI.reqShow = 'open';
        const b = $('[data-act="req-show"][data-v="open"]');
        if (b) setPressed(b);
      }
      updateReqs();
      const t = $('#req-text');
      if (t) t.focus();
    }
  });

  window.addEventListener('hashchange', route);

  /* ---------- Start ---------- */

  renderTabs();
  route();
  updateChrome();
})();
