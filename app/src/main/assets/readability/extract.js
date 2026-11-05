// Pocket AI: the main text of the open page for the AI.
// Article pages -> just the article (Mozilla Readability, as in Firefox Reader View), so menus, sidebars and
// "other headlines" aren't mixed into answers. Anything else -> the page's whole visible text.
// Returns JSON {article, published, text}.
(function () {
  function full() { return JSON.stringify({ article: false, published: '', text: document.body ? document.body.innerText : '' }); }
  try {
    var body = (document.body && document.body.innerText) || '';
    // Chinese/Japanese/Korean paragraphs are short in characters but dense, so use lower thresholds.
    var cjk = /[぀-ヿ㐀-鿿가-힯]/.test(body.slice(0, 4000));
    var readerable = false;
    try {
      readerable = typeof isProbablyReaderable === 'function' &&
        isProbablyReaderable(document, cjk ? { minContentLength: 40, minScore: 10 } : {});
    } catch (e) { /* the check crashes on some layouts (e.g. RTHK) */ }
    var meta = function (s) { var m = document.querySelector(s); return (m && m.content) || ''; };
    var declared = /article/i.test(meta('meta[property="og:type"]')) || !!meta('meta[property="article:published_time"]') ||
      !!document.querySelector('article') ||
      Array.prototype.some.call(document.querySelectorAll('script[type="application/ld+json"]'), function (s) {
        return /"@type"\s*:\s*"(News|Blog|Report)?Article"/.test(s.textContent);
      });
    if (!readerable && !declared) return full();
    var a = new Readability(document.cloneNode(true), cjk ? { charThreshold: 100 } : {}).parse();
    if (!a || !a.content) return full();
    // Article HTML -> text with paragraph breaks, in a detached document so nothing gets loaded.
    var d = document.implementation.createHTMLDocument('');
    var div = d.createElement('div');
    div.innerHTML = a.content;
    div.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,br,blockquote,pre,tr,figcaption,div').forEach(function (e) {
      e.insertAdjacentText('afterend', '\n');
    });
    var text = div.textContent.replace(/[ \t ]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
    if (text.length < (cjk ? 80 : 200) || text.length > body.length * 1.2) return full();
    return JSON.stringify({ article: true, published: a.publishedTime || meta('meta[property="article:published_time"]'), text: text });
  } catch (e) {
    return full();
  }
})()
