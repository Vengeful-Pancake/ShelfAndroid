package com.shelf.reader.ui.browser

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject
import org.json.JSONTokener

data class WebArticle(
    val url: String,
    val title: String,
    val author: String,
    val language: String,
    val text: String,
    val sourceText: String = text,
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    val webStates = mutableMapOf<String, Bundle>()
}

internal fun normalizeAddress(input: String): String {
    val value = input.trim()
    if (value.startsWith("http://", true)) return "https://" + value.substring(7)
    if (WebNavigationPolicy.isSecureWebUrl(value)) return value
    if (!value.contains(' ') && value.contains('.')) return "https://$value"
    return "https://duckduckgo.com/?q=${Uri.encode(value)}"
}

internal fun decodeArticle(raw: String, url: String): WebArticle? = runCatching {
    val first = JSONTokener(raw).nextValue()
    val json = when (first) {
        is String -> JSONObject(first)
        is JSONObject -> first
        else -> return null
    }
    if (!json.optBoolean("available")) return null
    WebArticle(
        url = url,
        title = json.optString("title").ifBlank { "Reader" },
        author = json.optString("author"),
        language = json.optString("language"),
        text = json.optString("text"),
        sourceText = json.optString("sourceText", json.optString("text")),
    ).takeIf { it.text.length >= 600 }
}.getOrNull()

internal const val ARTICLE_SCRIPT = """
(function(){
  try {
    var selectors = ['#content','#chaptercontent','#bookContent','.chapter-content','.reading-content','#readcontent'];
    var candidates = [];
    selectors.forEach(function(selector){
      document.querySelectorAll(selector).forEach(function(node){ candidates.push(node); });
    });
    if (!candidates.length) ['article','main','[role="main"]','.article','.post','.entry-content','.article-body'].forEach(function(selector){document.querySelectorAll(selector).forEach(function(n){candidates.push(n);});});
    if (!candidates.length && document.body) candidates.push(document.body);
    var source = candidates.sort(function(a,b){
      return (b.innerText || '').length - (a.innerText || '').length;
    })[0];
    if (!source) return JSON.stringify({available:false});
    var clean = source.cloneNode(true);
    var originalClean = source.cloneNode(true);
    var originalWalker = document.createTreeWalker(source, NodeFilter.SHOW_TEXT);
    var clonedWalker = document.createTreeWalker(originalClean, NodeFilter.SHOW_TEXT);
    var srcNode, dstNode;
    while ((srcNode = originalWalker.nextNode()) && (dstNode = clonedWalker.nextNode())) {
      dstNode.textContent = (window.shelfTranslator && window.shelfTranslator.originalText(srcNode)) || srcNode.orgn || srcNode.textContent;
    }
    originalClean.querySelectorAll('script,style,noscript,nav,header,footer,aside,form,button,svg,canvas,video,audio').forEach(function(n){n.remove();});
    originalClean.querySelectorAll('p,div,br,li,h1,h2,h3').forEach(function(n){n.appendChild(document.createTextNode('\n'));});
    var sourceText = (originalClean.textContent || '').replace(/[ \t]+/g,' ').replace(/\n\s*\n/g,'\n').trim();
    clean.querySelectorAll('script,style,noscript,nav,header,footer,aside,form,button,svg,canvas,video,audio').forEach(function(n){n.remove();});
    var text = (clean.innerText || clean.textContent || '').replace(/\s+/g,' ').trim();
    var paragraphs = clean.querySelectorAll('p').length;
    var available = text.length >= 700 && (paragraphs >= 3 || text.length >= 2000);
    var author = '';
    var authorMeta = document.querySelector('meta[name="author"],meta[property="article:author"]');
    if (authorMeta) author = authorMeta.content || '';
    return JSON.stringify({
      available: available,
      title: document.title || '',
      author: author,
      language: document.documentElement.lang || '',
      text: text.slice(0,700000),
      sourceText: sourceText.slice(0,700000)
    });
  } catch(e) {
    return JSON.stringify({available:false});
  }
})()
"""
