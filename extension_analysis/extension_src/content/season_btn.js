/**
 * season_btn.js — Ouvre le panneau Saison inline.
 * Utilise customElements.whenDefined('com-toolbar') car la shadow root
 * n'existe pas encore quand ce script s'exécute (panel.js est un module).
 */
(function () {
  function tryBind(toolbar) {
    try {
      var root = toolbar.shadowRoot;
      if (!root) return false;
      var btn = root.getElementById('button_show_season');
      if (!btn || btn._spBound) return false;
      btn._spBound = true;
      btn.addEventListener('click', function (e) {
        e.stopImmediatePropagation();
        if (typeof window.showSeasonPanel === 'function') {
          window.showSeasonPanel();
        } else {
          var api = typeof chrome !== 'undefined' ? chrome : browser;
          api.tabs.create({ url: api.runtime.getURL('/content/season.html') });
        }
      });
      return true;
    } catch (e) { return false; }
  }

  function attach() {
    var toolbar = document.getElementById('toolbar');
    if (!toolbar) return;
    if (tryBind(toolbar)) return;

    // Wait for com-toolbar to be upgraded
    if (typeof customElements !== 'undefined' && customElements.whenDefined) {
      customElements.whenDefined('com-toolbar').then(function () {
        setTimeout(function () { tryBind(toolbar); }, 60);
      }).catch(function () {});
    }

    // Polling fallback
    var n = 0, iv = setInterval(function () {
      if (tryBind(toolbar) || ++n > 50) clearInterval(iv);
    }, 120);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', attach);
  } else {
    attach();
  }
})();
