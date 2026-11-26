// The home page's screenshot tabs, platform tabs and release lookup. Without it the page
// still works: the first screenshot shows, every platform's note is one tab, and Download goes to
// the releases page.
(function () {
  'use strict';
  var REPO = 'benlazaro/clouseau';
  var RELEASES = 'https://github.com/' + REPO + '/releases';
  var EDGE = RELEASES + '/download/edge/clouseau-edge.jar';

  // ── Screenshots: tabs switch the picture and its caption ──
  var shots = document.getElementById('shots');
  if (shots) {
    var img = document.getElementById('shot-img');
    var link = document.getElementById('shot-link');
    var caption = document.getElementById('shot-caption');
    var shotTabs = shots.querySelectorAll('[role="tab"]');
    shotTabs.forEach(function (t) {
      new Image().src = t.dataset.src;  // load the others now, so switching is instant
      t.addEventListener('click', function () {
        shotTabs.forEach(function (o) { o.setAttribute('aria-selected', String(o === t)); });
        img.src = t.dataset.src; img.alt = t.dataset.alt; link.href = t.dataset.src;
        caption.textContent = t.dataset.caption;
      });
    });
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
