// Bridge marketing site — copy buttons + scroll reveal. No dependencies.

(function () {
  'use strict';

  /* ── copy to clipboard ───────────────────────────────── */
  function legacyCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return ok;
  }

  function feedback(btn, ok) {
    var label = btn.querySelector('.copy-text');
    var original = btn.getAttribute('data-label') || (label ? label.textContent : '');
    btn.setAttribute('data-label', original);
    if (label) label.textContent = ok ? 'Copied' : 'Press ⌘C';
    btn.classList.toggle('done', ok);
    window.setTimeout(function () {
      if (label) label.textContent = original;
      btn.classList.remove('done');
    }, 2000);
  }

  document.querySelectorAll('.copy').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var text = btn.getAttribute('data-copy') || '';
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(
          function () { feedback(btn, true); },
          function () { feedback(btn, legacyCopy(text)); }
        );
      } else {
        feedback(btn, legacyCopy(text));
      }
    });
  });

  /* ── scroll reveal (selector list mirrors styles.css) ── */
  var SELECTOR = [
    '.kicker', '.section-title', '.section-lede', '.section-foot',
    '.stats li', '.pcard', '.figure', '.steps li', '.feat',
    '.sec-list li', '.install-card', '.limit', '.faq details'
  ].join(',');

  var targets = document.querySelectorAll(SELECTOR);
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function showAll() {
    targets.forEach(function (el) { el.classList.add('is-in'); });
  }

  if (reduced || !('IntersectionObserver' in window)) {
    showAll();
    return;
  }

  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) return;
      entry.target.style.transitionDelay = (entry.target.dataset.revealDelay || '0ms');
      entry.target.classList.add('is-in');
      io.unobserve(entry.target);
    });
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });

  targets.forEach(function (el, i) {
    // stagger siblings a little, capped so nothing waits long
    el.dataset.revealDelay = ((i % 4) * 60) + 'ms';
    io.observe(el);
  });

  // Safety net: anything still hidden after load (e.g. observer never fired) shows anyway.
  window.addEventListener('load', function () {
    window.setTimeout(function () {
      targets.forEach(function (el) {
        var r = el.getBoundingClientRect();
        if (r.top < window.innerHeight && r.bottom > 0) el.classList.add('is-in');
      });
    }, 300);
  });
})();
