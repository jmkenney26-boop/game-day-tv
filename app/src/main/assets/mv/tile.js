// Runs in every frame of a Multiview tile's web page (main page and embedded players).
// Fills the tile with the page's video, keeps it playing, and mutes / unmutes it on command.
// Commands reach embedded players from other sites by postMessage, frame to frame.
(function () {
  if (window.__gdmv) return;
  var muted = true, styled = false;
  function media() { return document.querySelectorAll('video, audio'); }
  function applyMute() {
    media().forEach(function (m) {
      if (m.muted !== muted) m.muted = muted;
      if (!muted && m.volume === 0) m.volume = 1;
    });
  }
  function kids(msg) {
    for (var i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage(msg, '*'); } catch (e) {} }
  }
  function area(el) { var r = el.getBoundingClientRect(); return r.width * r.height; }
  function bestVideo() {
    var best = null, a = 0;
    document.querySelectorAll('video').forEach(function (v) {
      var s = Math.max(area(v), v.videoWidth * v.videoHeight);
      if (s > a) { a = s; best = v; }
    });
    return best;
  }
  function bestFrame() {
    var best = null, a = 0;
    document.querySelectorAll('iframe').forEach(function (f) {
      var r = f.getBoundingClientRect();
      if (r.width < 150 || r.height < 80) return;   // skip ad slots and trackers
      if (r.width * r.height > a) { a = r.width * r.height; best = f; }
    });
    return best;
  }
  function fill() {
    var t = bestVideo();
    if (!t || (area(t) < 150 * 80 && !t.videoWidth)) t = bestFrame() || t;
    if (!t) return;
    if (!styled) {
      var st = document.createElement('style');
      st.textContent = 'html,body{overflow:hidden!important;background:#000!important}';
      (document.head || document.documentElement).appendChild(st);
      styled = true;
    }
    var props = { position: 'fixed', left: '0', top: '0', width: '100vw', height: '100vh', 'max-width': 'none', 'max-height': 'none',
      'min-width': '0', 'min-height': '0', 'z-index': '2147483647', background: '#000', transform: 'none', margin: '0', border: '0', visibility: 'visible' };
    for (var k in props) if (t.style.getPropertyValue(k) !== props[k] || t.style.getPropertyPriority(k) !== 'important') t.style.setProperty(k, props[k], 'important');
    if (t.tagName === 'VIDEO') t.style.setProperty('object-fit', 'contain', 'important');
    // Parents with transforms trap position:fixed inside them.
    for (var n = t.parentElement; n && n !== document.documentElement; n = n.parentElement) {
      ['transform', 'will-change', 'filter', 'contain', 'perspective'].forEach(function (p) {
        n.style.setProperty(p, p === 'will-change' ? 'auto' : 'none', 'important');
      });
    }
  }
  function play() {
    media().forEach(function (m) {
      if (!m.paused || m.ended) return;
      var p = m.play();
      if (p && p.catch) p.catch(function () { m.muted = true; var q = m.play(); if (q && q.catch) q.catch(function () {}); });
    });
  }
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || d.gdmv !== 1) return;
    if (d.t === 'mute') { muted = !!d.m; applyMute(); kids(d); }
    else if (d.t === 'fill') { fill(); kids(d); }
    else if (d.t === 'play') { play(); kids(d); }
  });
  document.addEventListener('play', applyMute, true);
  setInterval(applyMute, 1500);
  window.__gdmv = {
    mute: function (m) { muted = !!m; applyMute(); kids({ gdmv: 1, t: 'mute', m: !!m }); },
    fill: function () { fill(); kids({ gdmv: 1, t: 'fill' }); },
    play: function () { play(); kids({ gdmv: 1, t: 'play' }); },
    // what's on the page, for the log: a video here, or a player embedded from another site
    info: function () { var v = bestVideo(), f = bestFrame(); return (v ? 'video ' + Math.round(area(v)) : 'no video') + (f ? ', player frame ' + (f.src || '').slice(0, 60) : ''); }
  };
})();
