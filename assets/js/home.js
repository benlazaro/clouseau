// The home page's screenshot carousel, platform tabs and release lookup. Without it the page
// still works: the screenshots are a row you can swipe, every platform's note is one tab, and Download goes to
// the releases page.
(function () {
  'use strict';
  var REPO = 'benlazaro/clouseau';
  var RELEASES = 'https://github.com/' + REPO + '/releases';
  var EDGE = RELEASES + '/download/edge/clouseau-edge.jar';

  // ── Screenshots: a carousel over a row that scrolls by itself, so swiping and the keyboard
  //    work natively. It moves on every few seconds until someone uses it, never under
  //    reduced motion, and stops while the pointer or focus is on it or the tab is hidden. ──
  var car = document.getElementById('shots');
  if (car) {
    var view = document.getElementById('car-viewport');
    var slides = view.querySelectorAll('.car-slide');
    var prev = document.getElementById('car-prev');
    var next = document.getElementById('car-next');
    var dotsBox = document.getElementById('car-dots');
    var index = 0, aim = null, timer = null, stopped = false, paused = false;
    var still = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (still) view.style.scrollBehavior = 'auto';

    var dots = Array.prototype.map.call(slides, function (sl, i) {
      var d = document.createElement('button');
      d.type = 'button';
      d.setAttribute('aria-label', 'Screenshot ' + (i + 1) + ': ' + sl.querySelector('img').alt);
      d.addEventListener('click', function () { stop(); go(i); });
      dotsBox.appendChild(d);
      return d;
    });
    var mark = function (i) {
      index = i;
      dots.forEach(function (d, k) { d.setAttribute('aria-current', String(k === i)); });
    };
    var go = function (i) {
      i = (i + slides.length) % slides.length;
      aim = i;                          // until the scroll gets there, ignore where it passes
      view.scrollTo({ left: slides[i].offsetLeft - view.offsetLeft });
      mark(i);
    };
    var stop = function () { stopped = true; clearInterval(timer); };
    prev.addEventListener('click', function () { stop(); go(index - 1); });
    next.addEventListener('click', function () { stop(); go(index + 1); });
    view.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowLeft') { e.preventDefault(); stop(); go(index - 1); }
      if (e.key === 'ArrowRight') { e.preventDefault(); stop(); go(index + 1); }
    });
    // A swipe or trackpad scroll: follow where it settles.
    var settle = null;
    view.addEventListener('scroll', function () {
      clearTimeout(settle);
      settle = setTimeout(function () {
        var at = Math.round(view.scrollLeft / view.clientWidth);
        if (aim !== null && at !== aim) return;
        aim = null;
        mark(at);
      }, 80);
    }, { passive: true });
    ['pointerdown', 'wheel', 'touchstart'].forEach(function (t) {
      view.addEventListener(t, function () { aim = null; stop(); }, { passive: true });
    });
    car.addEventListener('mouseenter', function () { paused = true; });
    car.addEventListener('mouseleave', function () { paused = false; });
    car.addEventListener('focusin', function () { paused = true; });
    car.addEventListener('focusout', function () { paused = false; });

    prev.hidden = next.hidden = dotsBox.hidden = false;
    mark(0);
    if (!still && slides.length > 1) {
      timer = setInterval(function () {
        if (!stopped && !paused && !document.hidden) go(index + 1);
      }, 6000);
    }
  }

  // ── Download: platform tabs, picked from the browser, and the latest release's files ──
  var NAMES = { windows: 'Windows', macos: 'macOS', linux: 'Linux', edge: 'Edge' };
  var files = {};                       // platform → { name, url }, once the release is known
  var current = 'windows';
  var tabs = document.querySelectorAll('.os-tabs [role="tab"]');
  var notes = document.querySelectorAll('.os-note');
  var button = document.getElementById('dl-button');
  var label = document.getElementById('dl-file');
  var hero = document.getElementById('hero-download');

  var show = function (os) {
    current = os;
    tabs.forEach(function (t) { t.setAttribute('aria-selected', String(t.dataset.os === os)); });
    notes.forEach(function (n) { n.hidden = n.dataset.os !== os; });
    if (os === 'edge') { button.href = EDGE; label.textContent = 'clouseau-edge.jar'; return; }
    var f = files[os];
    button.href = f ? f.url : RELEASES;
    label.textContent = f ? f.name : 'Download for ' + NAMES[os];
  };
  tabs.forEach(function (t) { t.addEventListener('click', function () { show(t.dataset.os); }); });

  var ua = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || navigator.userAgent;
  var mine = /mac/i.test(ua) ? 'macos' : /linux|x11/i.test(ua) && !/android/i.test(ua) ? 'linux' : 'windows';
  if (hero) hero.textContent = 'Download for ' + NAMES[mine];
  show(mine);

  // Pre-releases count: before 1.0 every release is one. The rolling "edge" release is skipped.
  if (window.fetch) {
    fetch('https://api.github.com/repos/' + REPO + '/releases?per_page=10')
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) {
        var rel = list.filter(function (r) { return !r.draft && r.tag_name !== 'edge'; })[0];
        if (!rel) return;
        (rel.assets || []).forEach(function (a) {
          var m = /-(windows|macos|linux)\.zip$/.exec(a.name);
          if (m) files[m[1]] = { name: a.name, url: a.browser_download_url };
        });
        show(current);
        var pill = document.getElementById('release-pill');
        document.getElementById('release-name').textContent = rel.tag_name.replace(/^v/, '');
        document.getElementById('release-link').href = rel.html_url;
        pill.hidden = false;
      })
      .catch(function () { /* offline or rate-limited: the releases page still works */ });
  }
})();
