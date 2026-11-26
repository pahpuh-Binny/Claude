/*
 * Multiview page script. The browser adds it to every frame before the
 * page's own scripts run. It:
 *   - stops pages from noticing they are in the background (so they don't
 *     pause their videos when you switch tabs or leave the app),
 *   - keeps track of the videos playing in the frame and reports it to the
 *     app (for the playback notification, mute buttons and multiview),
 *   - obeys "pause all", "play all" and "mute" from the app,
 *   - keeps a video going (muted) when another tab grabs the sound,
 *   - finds direct video links for the download and cast buttons,
 *   - hides common ad boxes and skips skippable video ads.
 * It never reads form fields, cookies or page text other than the title.
 */
(function () {
  'use strict';
  var NAME = '__MV_BRIDGE__';
  var CFG = /*__MV_CONFIG__*/ null;
  if (!CFG) return;
  var FLAG = NAME + '_on';
  if (window[FLAG]) return;
  try { Object.defineProperty(window, FLAG, { value: true }); } catch (e) { return; }

  var bridge = window[NAME];
  var send = function (o) {
    try { if (bridge) bridge.postMessage(JSON.stringify(o)); } catch (e) {}
  };
  var isTop = window === window.top;
  var now = Date.now;
  var host = '';
  try { host = location.hostname || ''; } catch (e) {}
  var allowed = false;
  (CFG.allow || []).forEach(function (h) {
    if (host === h || host.slice(-(h.length + 1)) === '.' + h) allowed = true;
  });

  // ---------------------------------------------------------------- 1. stay "visible"
  if (CFG.keepAlive) {
    try {
      var dp = Document.prototype;
      var fixed = function (obj, prop, val) {
        try {
          Object.defineProperty(obj, prop, {
            configurable: true, enumerable: true, get: function () { return val; }
          });
        } catch (e) {}
      };
      fixed(dp, 'hidden', false);
      fixed(dp, 'webkitHidden', false);
      fixed(dp, 'visibilityState', 'visible');
      fixed(dp, 'webkitVisibilityState', 'visible');
      try { dp.hasFocus = function () { return true; }; } catch (e) {}
      var swallow = function (e) {
        if (e.target === document || e.target === window) e.stopImmediatePropagation();
      };
      ['visibilitychange', 'webkitvisibilitychange', 'blur', 'freeze'].forEach(function (t) {
        window.addEventListener(t, swallow, true);
      });
    } catch (e) {}
  }

  // ---------------------------------------------------------------- 2. privacy signal
  if (CFG.gpc) {
    try {
      Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', {
        configurable: true, enumerable: true, get: function () { return true; }
      });
    } catch (e) {}
  }

  // ---------------------------------------------------------------- 3. media tracking
  var media = new Set();
  var info = new WeakMap();
  var tabMuted = false;
  var held = false;          // paused from the notification / headset
  var heldList = [];
  var lastInput = 0;
  var lastPeer = 0;          // another tab just started playing sound
  var pending = [];          // unexplained pauses waiting to be classified
  var origPlay = HTMLMediaElement.prototype.play;

  var userActive = function () { return now() - lastInput < 1500; };
  ['pointerdown', 'touchstart', 'mousedown', 'keydown', 'click'].forEach(function (t) {
    window.addEventListener(t, function (e) { if (e.isTrusted) lastInput = now(); }, true);
  });

  var stateOf = function (el) {
    var s = info.get(el);
    if (!s) { s = { resumes: [], mutedByUs: false, sniffed: '' }; info.set(el, s); }
    return s;
  };
  var applyMute = function (el) {
    var s = stateOf(el);
    if (tabMuted) {
      if (!el.muted) { s.mutedByUs = true; el.muted = true; }
    } else if (s.mutedByUs) {
      s.mutedByUs = false;
      el.muted = false;
    }
  };
  var MEDIA_EVENTS = ['play', 'playing', 'pause', 'ended', 'volumechange', 'loadedmetadata', 'emptied'];
  var track = function (el) {
    if (!el || media.has(el)) return;
    media.add(el);
    applyMute(el);
    // Players outside the page (new Audio()) or inside shadow DOM don't
    // send their events up to the window, so listen on them directly.
    var root = el.getRootNode ? el.getRootNode() : document;
    if (root !== document) {
      MEDIA_EVENTS.forEach(function (t) { el.addEventListener(t, onMediaEvent); });
    }
  };

  try {
    HTMLMediaElement.prototype.play = function () {
      track(this);
      if (tabMuted) applyMute(this);
      return origPlay.apply(this, arguments);
    };
  } catch (e) {}

  var httpSrc = function (el) {
    var s = el.currentSrc || el.src || '';
    return /^https?:/i.test(s) ? s : '';
  };
  var finite = function (n) { return isFinite(n) && n > 0 ? n : 0; };

  var sniff = function (el) {
    var src = httpSrc(el);
    var s = stateOf(el);
    if (!src || s.sniffed === src) return;
    s.sniffed = src;
    var poster = '';
    try { poster = el.poster || ''; } catch (e) {}
    send({
      type: 'media', src: src, kind: el.tagName === 'AUDIO' ? 'audio' : 'video',
      d: finite(el.duration), w: el.videoWidth || 0, h: el.videoHeight || 0,
      poster: /^https?:/i.test(poster) ? poster : '',
      title: isTop ? document.title : ''
    });
  };

  var isPlaying = function (el) { return !el.paused && !el.ended; };
  var audible = function (el) {
    if (!isPlaying(el) || el.muted || el.volume === 0) return false;
    var n = el.webkitAudioDecodedByteCount;
    return n === undefined || n > 0 || el.currentTime < 2;
  };

  var mainVideo = function () {
    var best = null, score = -1;
    media.forEach(function (el) {
      if (el.tagName !== 'VIDEO') return;
      var r = el.getBoundingClientRect();
      var area = Math.max(r.width * r.height, (el.videoWidth || 0) * (el.videoHeight || 0) / 4);
      var sc = (isPlaying(el) ? 1e9 : 0) + area;
      if (sc > score) { score = sc; best = el; }
    });
    return best;
  };

  var timer = 0, ticker = 0, lastCount = 0;
  var schedule = function (ms) {
    if (!timer) timer = setTimeout(report, ms === undefined ? 250 : ms);
  };
  function report() {
    timer = 0;
    var playing = 0, loud = false, count = 0;
    media.forEach(function (el) {
      if (!el.isConnected && el.paused && el.tagName === 'VIDEO') { media.delete(el); return; }
      count++;
      if (isPlaying(el)) playing++;
      if (audible(el)) loud = true;
    });
    if (!count && !lastCount) return;
    lastCount = count;
    var v = mainVideo(), main = null;
    if (v) {
      var r = v.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
      main = {
        src: httpSrc(v), t: v.currentTime || 0, d: finite(v.duration),
        w: v.videoWidth || 0, h: v.videoHeight || 0, playing: isPlaying(v),
        x: isTop ? r.left * dpr : -1, y: isTop ? r.top * dpr : -1,
        rw: isTop ? r.width * dpr : 0, rh: isTop ? r.height * dpr : 0
      };
    }
    send({
      type: 'state', top: isTop, playing: playing, audible: loud, count: count,
      muted: tabMuted, main: main, title: isTop ? document.title : ''
    });
    if (playing && !ticker) {
      ticker = setInterval(function () { schedule(0); }, 3000);
    } else if (!playing && ticker) {
      clearInterval(ticker); ticker = 0;
    }
  }

  // An unexplained pause right after another tab started playing sound
  // means Android gave the sound to that tab. Keep this video going, muted.
  function flushPending() {
    var t = now(), list = pending;
    pending = [];
    list.forEach(function (p) {
      var el = p.el, s = stateOf(el);
      if (t - p.t > 3000 || !el.paused || el.ended || held || el.currentSrc !== p.src) return;
      s.resumes = s.resumes.filter(function (x) { return t - x < 30000; });
      if (s.resumes.length >= 5) return;
      s.resumes.push(t);
      tabMuted = true;
      if (!el.muted) { s.mutedByUs = true; el.muted = true; }
      send({ type: 'autoMuted' });
      try { var pr = origPlay.call(el); if (pr && pr.catch) pr.catch(function () {}); } catch (e) {}
    });
  }

  var onPause = function (el) {
    if (!CFG.guard || held || el.ended || userActive()) return;
    pending.push({ el: el, t: now(), src: el.currentSrc });
    if (now() - lastPeer < 2500) setTimeout(flushPending, 120);
  };

  var onVolume = function (el) {
    if (!tabMuted || el.muted) return;
    if (userActive()) {
      // You unmuted the video yourself: this tab gets the sound.
      tabMuted = false;
      media.forEach(function (m) { stateOf(m).mutedByUs = false; });
      send({ type: 'userUnmuted' });
    } else {
      stateOf(el).mutedByUs = true;
      el.muted = true;
    }
  };

  function onMediaEvent(e) {
    var el = e.target;
    if (!(el instanceof HTMLMediaElement)) return;
    track(el);
    switch (e.type) {
      case 'play': applyMute(el); schedule(60); return;
      case 'playing': sniff(el); schedule(60); return;
      case 'pause': onPause(el); break;
      case 'volumechange': onVolume(el); break;
      case 'loadedmetadata': sniff(el); break;
    }
    schedule();
  }
  MEDIA_EVENTS.forEach(function (t) { window.addEventListener(t, onMediaEvent, true); });

  window.addEventListener('pagehide', function () {
    if (lastCount) send({ type: 'gone' });
  }, true);

  // ---------------------------------------------------------------- 4. commands from the app
  var seekTo = -1;
  var applySeek = function () {
    if (seekTo < 0) return;
    var v = mainVideo();
    if (!v) return;
    var go = function () {
      if (seekTo < 0) return;
      if (finite(v.duration) > seekTo) { try { v.currentTime = seekTo; } catch (e) {} }
      seekTo = -1;
    };
    if (v.readyState >= 1) go(); else v.addEventListener('loadedmetadata', go, { once: true });
  };
  window.addEventListener('loadedmetadata', function () { applySeek(); }, true);

  var onCommand = function (ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (!m || typeof m.cmd !== 'string') return;
    switch (m.cmd) {
      case 'config':
        tabMuted = !!m.muted;
        held = !!m.held;
        media.forEach(applyMute);
        break;
      case 'mute':
        tabMuted = !!m.v;
        media.forEach(applyMute);
        schedule(0);
        break;
      case 'pauseAll':
        held = true;
        heldList = [];
        media.forEach(function (el) {
          if (isPlaying(el)) { heldList.push(el); try { el.pause(); } catch (e) {} }
        });
        schedule(0);
        break;
      case 'playAll':
        held = false;
        var list = heldList.length ? heldList : [];
        heldList = [];
        list.forEach(function (el) {
          try { var p = origPlay.call(el); if (p && p.catch) p.catch(function () {}); } catch (e) {}
        });
        schedule(0);
        break;
      case 'peerAudio':
        lastPeer = now();
        if (pending.length) flushPending();
        break;
      case 'seek':
        seekTo = +m.t || -1;
        applySeek();
        break;
      case 'report':
        schedule(0);
        break;
    }
  };
  try { if (bridge) bridge.addEventListener('message', onCommand); } catch (e) {}

  // ---------------------------------------------------------------- 5. ads
  if (!allowed && CFG.css) {
    var addCss = function () {
      var root = document.documentElement;
      if (!root) return false;
      var st = document.createElement('style');
      st.textContent = CFG.css;
      root.appendChild(st);
      return true;
    };
    if (!addCss()) {
      var mo = new MutationObserver(function () { if (addCss()) mo.disconnect(); });
      mo.observe(document, { childList: true, subtree: true });
    }
  }
  if (!allowed && CFG.skipAds) {
    var adEl = null;
    setInterval(function () {
      if (!media.size) return;
      var skip = document.querySelector(
        '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
        '.videoAdUiSkipButton, button.skip-ad, .vjs-ad-skip');
      if (skip) { try { skip.click(); } catch (e) {} }
      var ad = document.querySelector('.ad-showing video, .ad-interrupting video');
      if (ad && finite(ad.duration) && ad.duration < 240) {
        if (adEl !== ad) { adEl = ad; adEl._mvWasMuted = ad.muted; }
        ad.muted = true;
        try { ad.playbackRate = 16; } catch (e) {}
      } else if (adEl) {
        try { adEl.playbackRate = 1; } catch (e) {}
        if (!adEl._mvWasMuted && !tabMuted) adEl.muted = false;
        adEl = null;
      }
    }, 700);
  }
})();
