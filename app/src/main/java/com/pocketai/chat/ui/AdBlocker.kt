package com.pocketai.chat.ui

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ad blocker for the in-app browser (the WebView can't run Firefox/Chrome extensions).
 *  1. Network: requests to known ad/tracker servers are answered with an empty response. Uses Steven Black's
 *     public hosts list (~78k domains; updated weekly, cached on the phone), plus a small built-in list until
 *     that's downloaded. Pages themselves (reddit.com, google.com, …) are never on it.
 *  2. Page: [HIDE_ADS_JS] hides ad boxes that sites serve from their own domain (Reddit promoted posts, Google
 *     sponsored results, ad slots), which network blocking can't catch.
 */
object AdBlocker {
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private val BUILTIN = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "amazon-adsystem.com", "taboola.com", "outbrain.com", "adnxs.com", "criteo.com", "criteo.net",
        "pubmatic.com", "rubiconproject.com", "openx.net", "moatads.com", "adsrvr.org", "scorecardresearch.com",
        "media.net", "smartadserver.com", "casalemedia.com", "3lift.com", "sharethrough.com", "teads.tv",
        "yieldmo.com", "adform.net", "bidswitch.net", "quantserve.com", "zedo.com", "popads.net",
    )

    @Volatile private var domains: Set<String> = BUILTIN
    @Volatile private var started = false

    /** Loads the cached list and refreshes it in the background when it's over a week old. */
    fun init(context: Context) {
        if (started) return
        started = true
        val file = File(context.filesDir, "adblock-hosts.txt")
        Thread {
            runCatching { if (file.exists()) domains = parse(file.readText()) + BUILTIN }
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) runCatching {
                val conn = URL(LIST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parse(text)
                if (parsed.size > 10_000) {
                    file.writeText(text)
                    domains = parsed + BUILTIN
                }
            }
        }.apply { isDaemon = true; name = "adblock-list" }.start()
    }

    private fun parse(text: String): Set<String> = HashSet<String>(100_000).apply {
        text.lineSequence().forEach { line ->
            if (line.startsWith("0.0.0.0 ")) {
                val d = line.substring(8).substringBefore('#').trim().lowercase()
                if (d.isNotEmpty() && d != "0.0.0.0") add(d)
            }
        }
    }

    /**
     * Services pages need to work, which the hosts list blocks anyway: bot checks (NYT's DataDome box stayed
     * empty and the page stuck), subscription/paywall popups (Piano — NY Daily News was left dimmed and stuck),
     * cookie-consent popups and captchas. Never blocked, including their subdomains.
     */
    private val NEVER_BLOCK = setOf(
        "datadome.co", "captcha-delivery.com", "challenges.cloudflare.com", "recaptcha.net", "hcaptcha.com",
        "arkoselabs.com", "perimeterx.net", "px-cdn.net", "px-cloud.net", "gstatic.com",
        "tinypass.com", "piano.io", "npttech.com", "cxense.com",
        "cookielaw.org", "onetrust.com", "privacy-mgmt.com", "sourcepoint.com", "sp-prod.net", "consensu.org",
        "didomi.io", "osano.com", "trustarc.com", "usercentrics.eu", "cookiebot.com", "fundingchoicesmessages.google.com",
    )

    private fun matches(host: String, set: Set<String>): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    /** "dd.nytimes.com" → "nytimes.com", "news.rthk.hk" → "rthk.hk", "www.bbc.co.uk" → "bbc.co.uk". */
    fun siteOf(host: String): String {
        val parts = host.lowercase().trimEnd('.').split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val secondLevel = parts[parts.size - 2]
        val keep = if (parts.last().length == 2 && secondLevel in setOf("com", "co", "org", "net", "gov", "edu", "ac", "ne", "or")) 3 else 2
        return parts.takeLast(keep).joinToString(".")
    }

    /**
     * True if a request from a page on [pageHost] to [host] should be blocked: [host] (or a parent domain) is
     * a known ad/tracker server, it isn't one of the page's own servers (sites break when their own services
     * are blocked; uBlock-style blockers do the same), and it isn't a service pages need ([NEVER_BLOCK]).
     */
    fun isBlocked(host: String?, pageHost: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if (!pageHost.isNullOrEmpty() && siteOf(h) == siteOf(pageHost)) return false
        if (matches(h, NEVER_BLOCK)) return false
        return matches(h, domains)
    }

    private val AD_NAG = Regex(
        """allow ads|ad ?block|disable (your )?ad|whitelist|turn off .*block|允許廣告|允许广告|廣告攔截|广告拦截|關閉.*廣告|关闭.*广告""",
        RegexOption.IGNORE_CASE,
    )

    /** True for "please allow ads / turn off your ad blocker" popups. */
    fun isAdNag(message: String?): Boolean = message != null && AD_NAG.containsMatchIn(message)

    private const val HIDE_CSS =
        // Google sponsored results
        "#tads,#tadsb,#bottomads,[data-text-ad]," +
            // Reddit promoted posts and ads in comments/sidebar
            "shreddit-ad-post,shreddit-comments-page-ad,shreddit-dynamic-ad-link,shreddit-sidebar-ad," +
            "[data-promoted=\"true\"],.promotedlink," +
            // Common ad slots (Google Ad Manager / AdSense, Taboola, Outbrain)
            "ins.adsbygoogle,.adsbygoogle,[id^=\"google_ads_iframe\"],[id^=\"div-gpt-ad\"],[data-google-query-id]," +
            "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication.com\"],iframe[src*=\"amazon-adsystem.com\"]," +
            "[id^=\"taboola\"],.trc_related_container,.OUTBRAIN,[data-ad-slot],[aria-label=\"Advertisement\"]," +
            // Empty reserved ad boxes (e.g. SCMP's gray "AdSlot" containers)
            "[data-qa*=\"AdSlot\"],[data-qa*=\"AdsSlot\"],[data-slot-collapse],[class*=\"ad-with-size\"]," +
            // Sticky "adhesion" ads stuck to the bottom of the screen (e.g. NY Daily News)
            "#mobile-adhesion,[id*=\"adhesion\"],[class*=\"adhesion\"]" +
            "{display:none!important}"

    /**
     * Removes "you're using an ad blocker / please allow ads" overlays built into the page (they appear in
     * WebViews even without blocking, because ads don't load there), plus their dark backdrop, and turns page
     * scrolling back on. Only overlays whose text matches are touched. Watches the page for a minute.
     */
    private const val NAG_JS = """
(function(){
  if (window.__pocketAiNag) return; window.__pocketAiNag = 1;
  var re = /(using an? ad ?blocker|disable (your )?ad ?block|turn off (your )?ad ?block|allow ads|whitelist (us|our site|this site)|ad ?block(er)? (is )?detected|允許廣告|允许广告|廣告攔截|广告拦截|關閉.{0,6}廣告|关闭.{0,6}广告)/i;
  function covers(e) { var r = e.getBoundingClientRect(); return r.width >= innerWidth * 0.9 && r.height >= innerHeight * 0.9; }
  function sweep() {
    var found = false, layers = [];
    document.querySelectorAll('body > *, body > * > *, body > * > * > *, body > * > * > * > *').forEach(function (e) {
      var cs = getComputedStyle(e);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') return;
      layers.push(e);
      if (re.test((e.innerText || '').slice(0, 3000))) { e.remove(); found = true; }
    });
    if (!found) return;
    layers.forEach(function (e) { if (e.isConnected && covers(e) && !(e.innerText || '').trim()) e.remove(); });
    [document.documentElement, document.body].forEach(function (e) {
      if (!e) return;
      e.style.setProperty('overflow', 'auto', 'important');
      if (getComputedStyle(e).position === 'fixed') e.style.setProperty('position', 'static', 'important');
    });
  }
  sweep();
  var t = null, obs = new MutationObserver(function () { clearTimeout(t); t = setTimeout(sweep, 400); });
  obs.observe(document.documentElement, { childList: true, subtree: true });
  setTimeout(function () { obs.disconnect(); }, 60000);
})();"""

    /**
     * ⋮ → Unblock page: removes everything covering the screen (stuck popups, dark backdrops, walls) and turns
     * scrolling back on, for sites that still get stuck.
     */
    const val UNBLOCK_PAGE_JS = """
(function(){
  var n = 0;
  document.querySelectorAll('body *').forEach(function (e) {
    if (!e.isConnected) return;
    var cs = getComputedStyle(e);
    if (cs.position !== 'fixed' && cs.position !== 'sticky' && cs.position !== 'absolute') return;
    var r = e.getBoundingClientRect(), z = parseInt(cs.zIndex, 10) || 0;
    // Layers covering most of the screen (backdrops, walls)…
    var cover = r.width >= innerWidth * 0.8 && r.height >= innerHeight * 0.5 && z > 0;
    // …and popup boxes floating mid-screen on top of everything (not headers stuck to the top edge).
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
})()"""

    /** Adds the ad-hiding stylesheet once per page (it also applies to ads inserted later) and the overlay remover. */
    val HIDE_ADS_JS =
        "(function(){if(document.getElementById('pocketai-adblock'))return;" +
            "var s=document.createElement('style');s.id='pocketai-adblock';s.textContent='" + HIDE_CSS.replace("'", "\\'") + "';" +
            "(document.head||document.documentElement).appendChild(s);})();" + NAG_JS
}
