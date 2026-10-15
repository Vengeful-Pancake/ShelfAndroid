package com.shelf.reader.ui.reader

import com.shelf.reader.data.ReaderSettings

/**
 * Wraps sanitised chapter HTML in a page whose look is driven entirely by reading
 * settings. This is the phone version of the desktop app's `.pane` styles: publisher
 * CSS was already dropped during parsing, so nothing here has to fight it.
 */
object ReaderHtml {

    const val ORIGIN = "https://shelf.localhost"
    const val RESOURCE_PREFIX = "/res/"

    /**
     * Gives every book its own resource namespace. EPUBs commonly reuse paths such as
     * `images/cover.jpg`; without the book id in the URL, WebView can serve an image
     * cached while a different book was open. The version invalidates that namespace
     * when a file at the same document URI changes.
     */
    fun resourceUrl(bookId: String, version: String, path: String): String =
        ORIGIN + RESOURCE_PREFIX + android.net.Uri.encode(bookId) + "/" +
            android.net.Uri.encode(path, "/") + "?v=" + android.net.Uri.encode(version)

    data class ResourceRequest(val bookId: String, val version: String, val path: String)

    fun resourceRequest(url: android.net.Uri): ResourceRequest? {
        if (url.scheme != "https" || url.host != "shelf.localhost") return null

        val encoded = url.encodedPath?.removePrefix(RESOURCE_PREFIX) ?: return null
        val separator = encoded.indexOf('/')
        if (separator <= 0 || separator == encoded.lastIndex) return null

        val bookId = android.net.Uri.decode(encoded.substring(0, separator))
        val path = android.net.Uri.decode(encoded.substring(separator + 1))
        val version = url.getQueryParameter("v").orEmpty()
        if (bookId.isBlank() || version.isBlank() || path.isBlank()) return null
        return ResourceRequest(bookId, version, path)
    }

    /**
     * One section of the book. Sections stack inside `#pane`, so continuous scrolling
     * can append the next one below without reloading. [next] is the spine index that
     * follows this section, or -1 at the end of the book.
     */
    fun section(
        index: Int,
        next: Int,
        chapterLabel: String,
        bodyHtml: String,
        transform: (String) -> String,
    ): String = buildString {
        append("<section class=\"shelf-sec\" data-index=\"").append(index)
        append("\" data-next=\"").append(next).append("\">")
        if (chapterLabel.isNotBlank()) {
            append("<div class=\"chapter-head\">").append(escape(transform(chapterLabel))).append("</div>")
        }
        append(transform(bodyHtml))
        append("</section>")
    }

    fun page(
        sectionHtml: String,
        settings: ReaderSettings,
        topInsetPx: Int,
        bottomInsetPx: Int,
    ): String {
        val dark = settings.isDarkPage
        val rule = if (dark) "rgba(230,227,221,.28)" else "rgba(30,30,30,.22)"
        val head = buildString {
            append(HEAD_OPEN)
            append(":root{")
            append("--bg:").append(settings.background).append(';')
            append("--fg:").append(settings.foreground).append(';')
            append("--font:").append(settings.font.css).append(';')
            append("--size:").append(settings.textSize).append("px;")
            append("--line:").append(settings.lineHeight).append(';')
            append("--gutter:").append(settings.margin).append("px;")
            append("--align:").append(settings.align).append(';')
            append("--cols:").append(if (settings.twoColumns) 2 else 1).append(';')
            append("--col-width:").append(if (settings.twoColumns) "45%" else "100%").append(';')
            append("--rule:").append(rule).append(';')
            append("--pad-top:").append(topInsetPx).append("px;")
            append("--pad-bottom:").append(bottomInsetPx).append("px;")
            append('}')
            append(BASE_CSS)
            append(HEAD_CLOSE)
        }

        return buildString {
            append(head)
            append(if (settings.indent) "<body class=\"indent\">" else "<body>")
            append("<main id=\"pane\">")
            append(sectionHtml)
            append("</main>")
            append("<script>window.shelfContinuous=").append(settings.continuousScroll).append(";</script>")
            append(SCRIPT)
            append("</body></html>")
        }
    }

    /** Wraps one merged front-matter file so links and fragments can find it. */
    fun frontPart(index: Int, html: String): String =
        "<div class=\"shelf-part\" id=\"shelf-part-$index\" data-part=\"$index\">$html</div>"

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val HEAD_OPEN =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, " +
            "maximum-scale=1, user-scalable=no, viewport-fit=cover\"><style>"

    private const val HEAD_CLOSE = "</style></head>"

    private const val BASE_CSS = """
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;padding:0;background:var(--bg);color:var(--fg)}
body{
  font-family:var(--font);
  font-size:var(--size);
  line-height:var(--line);
  text-align:var(--align);
  padding:var(--pad-top) var(--gutter) var(--pad-bottom);
  overflow-wrap:break-word;
  word-wrap:break-word;
  -webkit-text-size-adjust:100%;
  text-rendering:optimizeLegibility;
}
#pane{
  max-width:100%;
  column-count:var(--cols);
  column-width:var(--col-width);
  column-gap:calc(var(--gutter) * 2);
}
p{margin:0 0 1.05em}
img,svg{
  max-width:100%!important;
  height:auto!important;
  display:block;
  margin:1.4em auto;
  break-inside:avoid;
}
h1,h2,h3,h4{line-height:1.3;margin:1.5em 0 .7em;text-align:initial;font-weight:600;break-after:avoid}
h1{font-size:1.45em}h2{font-size:1.25em}h3{font-size:1.1em}h4{font-size:1em}
blockquote{margin:1.3em 0;padding-left:1.1em;border-left:3px solid currentColor;opacity:.82}
hr{border:0;border-top:1px solid currentColor;opacity:.22;margin:2em 0}
mark.shelf-highlight{background:rgba(221,143,174,.6);color:inherit;padding:0 2px;border-radius:2px;box-shadow:0 0 0 1px rgba(221,143,174,.8)}
table{width:100%;border-collapse:collapse;margin:1.2em 0;font-size:.92em;display:block;overflow-x:auto}
td,th{border:1px solid var(--rule);padding:6px 9px}
a{color:inherit;text-decoration:underline;text-underline-offset:2px}
pre{white-space:pre-wrap;overflow-x:auto}
ul,ol{padding-left:1.4em}
sup,sub{line-height:0}
.chapter-head{
  margin:0 0 1.6em;padding-bottom:.8em;border-bottom:1px solid var(--rule);
  font-size:.72em;letter-spacing:.1em;text-transform:uppercase;opacity:.55;text-align:initial;
}
.shelf-sec + .shelf-sec{margin-top:3.2em;padding-top:1.4em;border-top:1px solid var(--rule)}
.shelf-part + .shelf-part{margin-top:2.4em}
.indent #pane p{text-indent:2em}
.indent #pane p:first-of-type{text-indent:0}
::selection{background:rgba(221,143,174,.35)}
"""

    /** Live-updates the open page when reading settings change, without a reload. */
    fun applySettingsScript(settings: ReaderSettings, topPadDp: Int, bottomPadDp: Int): String {
        val rule = if (settings.isDarkPage) "rgba(230,227,221,.28)" else "rgba(30,30,30,.22)"
        return buildString {
            append("(function(){var r=document.documentElement.style;")
            append("r.setProperty('--bg','").append(settings.background).append("');")
            append("r.setProperty('--fg','").append(settings.foreground).append("');")
            append("r.setProperty('--font',\"").append(settings.font.css).append("\");")
            append("r.setProperty('--size','").append(settings.textSize).append("px');")
            append("r.setProperty('--line','").append(settings.lineHeight).append("');")
            append("r.setProperty('--gutter','").append(settings.margin).append("px');")
            append("r.setProperty('--align','").append(settings.align).append("');")
            append("r.setProperty('--cols','").append(if (settings.twoColumns) 2 else 1).append("');")
            append("r.setProperty('--col-width','").append(if (settings.twoColumns) "45%" else "100%").append("');")
            append("r.setProperty('--rule','").append(rule).append("');")
            append("r.setProperty('--pad-top','").append(topPadDp).append("px');")
            append("r.setProperty('--pad-bottom','").append(bottomPadDp).append("px');")
            append("document.body.classList.toggle('indent',").append(settings.indent).append(");")
            append("window.shelfContinuous=").append(settings.continuousScroll).append(";")
            append("if(window.shelfCheckMore)window.shelfCheckMore();")
            append("})()")
        }
    }

    /**
     * Touch handling lives in the page rather than in Compose. Overlaying a gesture
     * detector on a WebView means fighting it for every scroll; doing it here keeps
     * link taps, text selection and scrolling behaving normally.
     */
    private const val SCRIPT = """
<script>
(function(){
  var lastSent = -1, ticking = false, lastY = 0, lastSection = null;
  var wantNext = -1, wantAt = 0;

  function sections(){ return document.querySelectorAll('section.shelf-sec'); }

  function topOf(el, first){
    return first ? 0 : el.getBoundingClientRect().top + window.scrollY;
  }

  /** The section under the upper quarter of the screen is the one being read. */
  function current(){
    var list = sections();
    if (!list.length) return null;
    var probe = window.scrollY + window.innerHeight * 0.25;
    var cur = list[0];
    for (var i = 1; i < list.length; i++){
      if (topOf(list[i], false) <= probe) cur = list[i]; else break;
    }
    return cur;
  }

  function span(sec){
    var first = sec === sections()[0];
    var top = topOf(sec, first);
    var bottom = sec.getBoundingClientRect().bottom + window.scrollY;
    var next = sec.nextElementSibling;
    if (!next || !next.classList.contains('shelf-sec')) bottom = document.documentElement.scrollHeight;
    return { top: top, len: Math.max(1, bottom - top - window.innerHeight) };
  }

  /** Progress through the current section, 0..1. */
  function fraction(sec){
    if (!sec) return 0;
    var s = span(sec);
    var f = (window.scrollY - s.top) / s.len;
    return f < 0 ? 0 : (f > 1 ? 1 : f);
  }

  function checkMore(){
    if (!window.shelfContinuous) return;
    var list = sections();
    if (!list.length) return;
    var last = list[list.length - 1];
    var next = parseInt(last.getAttribute('data-next'), 10);
    if (isNaN(next) || next < 0) return;
    var remaining = document.documentElement.scrollHeight - (window.scrollY + window.innerHeight);
    if (remaining > window.innerHeight * 2.5) return;
    // One request at a time; retry if the answer never comes (a section failed to load).
    if (wantNext === next && Date.now() - wantAt < 5000) return;
    wantNext = next; wantAt = Date.now();
    Shelf.onNeedNext(next);
  }
  window.shelfCheckMore = checkMore;

  function report(){
    var sec = current();
    if (sec){
      var idx = parseInt(sec.getAttribute('data-index'), 10);
      if (idx !== lastSection){
        lastSection = idx;
        lastSent = -1;
        Shelf.onSection(idx);
      }
    }
    var f = fraction(sec);
    if (Math.abs(f - lastSent) > 0.002){
      lastSent = f;
      Shelf.onScroll(f);
    }
    var y = window.scrollY;
    if (Math.abs(y - lastY) > 12){
      Shelf.onScrollDirection(y > lastY && y > 90);
      lastY = y;
    }
    checkMore();
  }

  window.addEventListener('scroll', function(){
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(function(){ ticking = false; report(); });
  }, {passive:true});

  /** Continuous scrolling: add the next section under the last one. */
  window.shelfAppend = function(index, html){
    var list = sections();
    if (!list.length) return false;
    var last = list[list.length - 1];
    if (parseInt(last.getAttribute('data-next'), 10) !== index) return false;
    if (document.querySelector('section.shelf-sec[data-index="' + index + '"]')) return false;
    document.getElementById('pane').insertAdjacentHTML('beforeend', html);
    wantNext = -1;
    prune();
    setTimeout(checkMore, 60);
    return true;
  };

  /** Keep memory flat on long sessions: drop sections well above the reader. */
  function prune(){
    var list = sections();
    var cur = current();
    var i = 0;
    while (list.length - i > 4 && list[i] !== cur){
      var sec = list[i];
      var rect = sec.getBoundingClientRect();
      if (rect.bottom > -window.innerHeight) break;
      // Anchor on the section being read so the text on screen does not move.
      var anchor = cur ? cur.getBoundingClientRect().top : 0;
      sec.parentNode.removeChild(sec);
      if (cur) window.scrollBy(0, cur.getBoundingClientRect().top - anchor);
      lastY = window.scrollY;
      i++;
    }
  }

  function scrollToSection(index, fragment){
    var sec = document.querySelector('section.shelf-sec[data-index="' + index + '"]');
    var part = document.getElementById('shelf-part-' + index);
    var target = null;
    if (fragment){
      var scope = part || sec;
      if (scope) target = scope.querySelector('[id="' + fragment + '"],[name="' + fragment + '"]');
    }
    if (!target) target = part || sec;
    if (!target) return false;
    target.scrollIntoView(true);
    lastY = window.scrollY;
    return true;
  }

  document.addEventListener('click', function(e){
    var a = e.target.closest ? e.target.closest('a') : null;
    if (a){
      var chapter = a.getAttribute('data-chapter');
      if (chapter !== null){
        e.preventDefault();
        var target = parseInt(chapter,10), frag = a.getAttribute('data-fragment') || '';
        if (scrollToSection(target, frag)) return;
        Shelf.onInternalLink(target, frag);
        return;
      }
      if (a.getAttribute('href') === '#'){ e.preventDefault(); return; }
    }
  }, true);

  var startX = 0, startY = 0, startT = 0, moved = false;

  document.addEventListener('touchstart', function(e){
    if (e.touches.length !== 1) { moved = true; return; }
    startX = e.touches[0].clientX;
    startY = e.touches[0].clientY;
    startT = Date.now();
    moved = false;
  }, {passive:true});

  document.addEventListener('touchend', function(e){
    if (moved) return;
    var t = e.changedTouches[0];
    var dx = t.clientX - startX;
    var dy = t.clientY - startY;
    var dt = Date.now() - startT;

    if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.8 && dt < 600){
      Shelf.onSwipe(dx < 0 ? 1 : -1);
      return;
    }

    if (Math.abs(dx) < 14 && Math.abs(dy) < 14 && dt < 400){
      if (window.getSelection && String(window.getSelection()).length > 0) return;
      var zone = t.clientX / window.innerWidth;
      Shelf.onTap(zone < 0.28 ? -1 : (zone > 0.72 ? 1 : 0));
    }
  }, {passive:true});

  window.shelfScrollTo = function(f){
    var sec = current();
    if (!sec){ window.scrollTo(0, 0); return; }
    var s = span(sec);
    window.scrollTo(0, Math.max(0, Math.round(s.top + f * s.len)));
    lastY = window.scrollY;
  };

  window.shelfScrollToId = function(id){
    var el = document.getElementById(id);
    if (!el) el = document.querySelector('[name="' + id + '"]');
    if (el) { el.scrollIntoView(true); lastY = window.scrollY; return true; }
    return false;
  };

  window.shelfPage = function(dir){
    var step = window.innerHeight * 0.86;
    var before = window.scrollY;
    window.scrollBy({top: dir * step, left: 0, behavior: 'smooth'});
    return before;
  };

  window.shelfAtEnd = function(){
    var max = document.documentElement.scrollHeight - window.innerHeight;
    return max <= 0 || window.scrollY >= max - 4;
  };

  window.shelfAtStart = function(){ return window.scrollY <= 4; };

  function shelfMark(text, root, focus){
    if (!text || !root) return false;
    var node, walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    while (node = walker.nextNode()) {
      if (node.parentElement && node.parentElement.closest('mark.shelf-highlight')) continue;
      var idx = node.nodeValue.toLocaleLowerCase().indexOf(String(text).toLocaleLowerCase());
      if (idx !== -1) {
        var range = document.createRange();
        range.setStart(node, idx);
        range.setEnd(node, idx + text.length);

        var mark = document.createElement('mark');
        mark.className = 'shelf-highlight';
        range.surroundContents(mark);
        if (focus) mark.scrollIntoView({behavior: 'smooth', block: 'center'});
        return true;
      }
    }
    return false;
  }

  /** Read-aloud: mark one passage anywhere on the page and bring it into view. */
  window.shelfHighlight = function(text){
    window.shelfClearHighlight();
    return shelfMark(text, document.body, true);
  };

  /**
   * Saved highlights, as [sectionIndex, text] pairs, each marked only inside its own
   * section. [focus] (optional) is scrolled into view — used for search results.
   */
  window.shelfHighlights = function(pairs, focus){
    window.shelfClearHighlight();
    window.shelfAddHighlights(pairs, focus);
  };

  window.shelfAddHighlights = function(pairs, focus){
    if (!Array.isArray(pairs)) return;
    pairs.forEach(function(p){
      var sec = document.querySelector('section.shelf-sec[data-index="' + p[0] + '"]');
      shelfMark(p[1], sec, focus && p[1] === focus);
    });
  };

  window.shelfSelection = function(){
    return window.getSelection ? String(window.getSelection()).trim() : '';
  };

  window.shelfClearHighlight = function(){
    var marks = document.querySelectorAll('mark.shelf-highlight');
    marks.forEach(function(m){
      var parent = m.parentNode;
      while(m.firstChild) parent.insertBefore(m.firstChild, m);
      parent.removeChild(m);
    });
    document.body.normalize();
  };

  function ready(){ Shelf.onReady(); setTimeout(checkMore, 250); }
  if (document.readyState !== 'loading') ready();
  else document.addEventListener('DOMContentLoaded', ready);
})();
</script>
"""
}
