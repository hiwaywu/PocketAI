// Pocket AI bridge (runs in every top-level page). The app sends {cmd, id} over a native port:
//  - "extract": reply with the page's text for the AI. Article pages -> just the article (Mozilla Readability,
//    as in Firefox Reader View), so menus, sidebars and "other headlines" aren't mixed into answers.
//    Anything else (search results, feeds, forums) -> all visible text.
//  - "unblock": remove whatever covers the page (stuck popups, backdrops, walls) and turn scrolling back on.
(function () {
  if (window !== window.top) return;

  function fullText() {
    return { article: false, published: '', text: document.body ? document.body.innerText : '' };
  }

  function extractPage() {
    try {
      var body = (document.body && document.body.innerText) || '';
      // Chinese/Japanese/Korean paragraphs are short in characters but dense, so use lower thresholds.
      var cjk = /[぀-ヿ㐀-鿿가-힯]/.test(body.slice(0, 4000));
      var readerable = false;
      try {
        readerable = typeof isProbablyReaderable === 'function' &&
          isProbablyReaderable(document, cjk ? { minContentLength: 40, minScore: 10 } : {});
      } catch (e) { /* the check throws on some layouts (e.g. RTHK) */ }
      var meta = function (s) { var m = document.querySelector(s); return (m && m.content) || ''; };
      var declared = /article/i.test(meta('meta[property="og:type"]')) || !!meta('meta[property="article:published_time"]') ||
        !!document.querySelector('article') ||
        Array.prototype.some.call(document.querySelectorAll('script[type="application/ld+json"]'), function (s) {
          return /"@type"\s*:\s*"(News|Blog|Report)?Article"/.test(s.textContent);
        });
      if (!readerable && !declared) return fullText();
      var a = new Readability(document.cloneNode(true), cjk ? { charThreshold: 100 } : {}).parse();
      if (!a || !a.content) return fullText();
      // Article HTML -> text with paragraph breaks, in a detached document so nothing gets loaded.
      var d = document.implementation.createHTMLDocument('');
      var div = d.createElement('div');
      div.innerHTML = a.content;
      div.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,br,blockquote,pre,tr,figcaption,div').forEach(function (e) {
        e.insertAdjacentText('afterend', '\n');
      });
      var text = div.textContent.replace(/[ \t ]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
      if (text.length < (cjk ? 80 : 200) || text.length > body.length * 1.2) return fullText();
      return { article: true, published: a.publishedTime || meta('meta[property="article:published_time"]'), text: text };
    } catch (e) {
      return fullText();
    }
  }

  function unblockPage() {
    var n = 0;
    document.querySelectorAll('body *').forEach(function (e) {
      if (!e.isConnected) return;
      var cs = getComputedStyle(e);
      if (cs.position !== 'fixed' && cs.position !== 'sticky' && cs.position !== 'absolute') return;
      var r = e.getBoundingClientRect(), z = parseInt(cs.zIndex, 10) || 0;
      // Layers covering most of the screen (backdrops, walls) and popups floating mid-screen on top of
      // everything; headers stuck to the top edge are kept.
      var cover = r.width >= innerWidth * 0.8 && r.height >= innerHeight * 0.5 && z > 0;
      var popup = cs.position === 'fixed' && z >= 1000 && r.width >= innerWidth * 0.5 && r.top > 40 && r.bottom < innerHeight - 10;
      if (cover || popup) { e.remove(); n++; }
    });
    [document.documentElement, document.body].forEach(function (e) {
      if (!e) return;
      e.style.setProperty('overflow', 'auto', 'important');
      e.style.setProperty('filter', 'none', 'important');
      if (getComputedStyle(e).position === 'fixed') e.style.setProperty('position', 'static', 'important');
    });
    return n;
  }

  var port = browser.runtime.connectNative('browser');
  port.onMessage.addListener(function (msg) {
    if (!msg) return;
    if (msg.cmd === 'extract') {
      var r = extractPage();
      port.postMessage({ type: 'page', id: msg.id, article: r.article, published: r.published || '', text: r.text });
    } else if (msg.cmd === 'unblock') {
      port.postMessage({ type: 'unblocked', id: msg.id, removed: unblockPage() });
    }
  });
})();
