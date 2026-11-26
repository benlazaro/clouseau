// The home page's screenshot carousel, platform tabs and release lookup. Without it the page
// still works: the screenshots are a row you can swipe, every platform's note is one tab, and Download goes to
// the releases page.
(function () {
  'use strict';
  var REPO = 'benlazaro/clouseau';
  var RELEASES = 'https://github.com/' + REPO + '/releases';
  var EDGE = RELEASES + '/download/edge/clouseau-edge.jar';

  // ── Screenshots: the row of slides becomes a stack that crossfades from one to the next
  //    (arrows, dots, ← →, or a swipe). It moves on every few seconds until someone uses it,
  //    never under reduced motion, and stops while the pointer or focus is on it or the tab is hidden. ──
  var car = document.getElementById('shots');
  if (car) {
    var view = document.getElementById('car-viewport');
    // In the Light theme, start with the Light screenshot.
    var lightShot = view.querySelector('.car-slide img[src*="main-view-light"]');
    if (window.clouseauTheme && clouseauTheme.get() === 'light' && lightShot) {
      view.insertBefore(lightShot.closest('.car-slide'), view.firstElementChild);
    }
    var slides = view.querySelectorAll('.car-slide');
    Array.prototype.forEach.call(slides, function (sl, i) { sl.setAttribute('aria-label', (i + 1) + ' of ' + slides.length); });
    var prev = document.getElementById('car-prev');
    var next = document.getElementById('car-next');
    var dotsBox = document.getElementById('car-dots');
    var index = 0;
    var still = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    view.classList.add('is-fading');      // a stack, not a row: see .car-viewport.is-fading

    var dots = Array.prototype.map.call(slides, function (sl, i) {
      var d = document.createElement('button');
      d.type = 'button';
      d.setAttribute('aria-label', 'Screenshot ' + (i + 1) + ': ' + sl.querySelector('img').alt);
      d.addEventListener('click', function () { stop(); go(i); });
      dotsBox.appendChild(d);
      return d;
    });
    var go = function (i) {
      i = (i + slides.length) % slides.length;
      index = i;
      Array.prototype.forEach.call(slides, function (sl, k) { sl.classList.toggle('is-current', k === i); });
      dots.forEach(function (d, k) { d.setAttribute('aria-current', String(k === i)); });
    };
    var stop = function () { car.classList.remove('is-playing'); };
    prev.addEventListener('click', function () { stop(); go(index - 1); });
    next.addEventListener('click', function () { stop(); go(index + 1); });
    view.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowLeft') { e.preventDefault(); stop(); go(index - 1); }
      if (e.key === 'ArrowRight') { e.preventDefault(); stop(); go(index + 1); }
    });
    // A swipe: a mostly sideways drag of 40px or more steps one screenshot that way.
    var startX = null, startY = null;
    view.addEventListener('touchstart', function (e) {
      stop();
      startX = e.touches[0].clientX; startY = e.touches[0].clientY;
    }, { passive: true });
    view.addEventListener('touchend', function (e) {
      if (startX === null) return;
      var dx = e.changedTouches[0].clientX - startX, dy = e.changedTouches[0].clientY - startY;
      startX = null;
      if (Math.abs(dx) >= 40 && Math.abs(dx) > Math.abs(dy)) go(index + (dx < 0 ? 1 : -1));
    }, { passive: true });
    view.addEventListener('pointerdown', stop, { passive: true });
    // Paused (the fill on the current dot holds) while the pointer or focus is on the
    // carousel or the tab is hidden.
    var hovered = false, focused = false;
    var pause = function () { car.classList.toggle('is-paused', hovered || focused || document.hidden); };
    car.addEventListener('mouseenter', function () { hovered = true; pause(); });
    car.addEventListener('mouseleave', function () { hovered = false; pause(); });
    car.addEventListener('focusin', function () { focused = true; pause(); });
    car.addEventListener('focusout', function () { focused = false; pause(); });
    document.addEventListener('visibilitychange', pause);

    // Autoplay: the current dot fills over DWELL (the car-progress animation); when it's full,
    // the next screenshot fades in. Once someone uses the carousel it stays where they put it.
    var DWELL = 6;                      // seconds
    car.style.setProperty('--dwell', DWELL + 's');
    dotsBox.addEventListener('animationend', function (e) {
      if (e.animationName === 'car-progress' && car.classList.contains('is-playing')) go(index + 1);
    });

    prev.hidden = next.hidden = dotsBox.hidden = false;
    go(0);
    if (!still && slides.length > 1) car.classList.add('is-playing');
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
