package com.mourad.worldnews

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

data class Article(
    val title: String,
    val link: String,
    val summary: String,
    val date: Long,
    val source: String
)

class MainActivity : AppCompatActivity() {

    private val feeds = linkedMapOf(
        "BBC" to "https://feeds.bbci.co.uk/news/world/rss.xml",
        "Al Jazeera" to "https://www.aljazeera.com/xml/rss/all.xml",
        "France 24" to "https://www.france24.com/fr/rss",
        "Le Monde" to "https://www.lemonde.fr/international/rss_full.xml"
    )

    private var all: List<Article> = emptyList()
    private var filter: String = "Tout"
    private val shown = mutableListOf<Article>()
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: NewsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "World News"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        (listOf("Tout") + feeds.keys).forEach { name ->
            bar.addView(Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { filter = name; applyFilter() }
            })
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(bar)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        adapter = NewsAdapter()
        val list = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shown[pos].link)))
            }
        }
        swipe = SwipeRefreshLayout(this).apply {
            addView(list)
            setOnRefreshListener { load() }
        }
        root.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        swipe.isRefreshing = true
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                feeds.map { (name, url) ->
                    async { runCatching { fetch(name, url) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten()
            }
            swipe.isRefreshing = false
            if (results.isEmpty()) {
                Toast.makeText(this@MainActivity, "Impossible de charger les news. Vérifie ta connexion.", Toast.LENGTH_LONG).show()
            }
            all = results.sortedByDescending { it.date }
            applyFilter()
        }
    }

    private fun applyFilter() {
        shown.clear()
        shown.addAll(if (filter == "Tout") all else all.filter { it.source == filter })
        adapter.notifyDataSetChanged()
    }

    private fun fetch(source: String, url: String): List<Article> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WorldNews/1.0")
        }
        conn.inputStream.use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, null)
            val out = mutableListOf<Article>()
            var title = ""; var link = ""; var desc = ""; var date = 0L
            var inItem = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "item" -> { inItem = true; title = ""; link = ""; desc = ""; date = 0L }
                        "title" -> if (inItem) title = parser.nextText().trim()
                        "link" -> if (inItem) link = parser.nextText().trim()
                        "description" -> if (inItem) desc = parser.nextText()
                        "pubDate" -> if (inItem) date = parseDate(parser.nextText().trim())
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "item") {
                        inItem = false
                        if (title.isNotEmpty() && link.isNotEmpty()) {
                            val clean = HtmlCompat.fromHtml(desc, HtmlCompat.FROM_HTML_MODE_COMPACT)
                                .toString().replace('\uFFFC', ' ').trim()
                            out.add(Article(title, link, clean, date, source))
                        }
                    }
                }
                ev = parser.next()
            }
            return out
        }
    }

    private fun parseDate(s: String): Long {
        for (p in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz")) {
            try { return SimpleDateFormat(p, Locale.ENGLISH).parse(s)!!.time } catch (_: Exception) {}
        }
        return 0L
    }

    inner class NewsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val a = shown[position]
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            box.addView(TextView(this@MainActivity).apply {
                text = a.title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            })
            val when_ = if (a.date > 0)
                android.text.format.DateUtils.getRelativeTimeSpanString(a.date).toString() else ""
            box.addView(TextView(this@MainActivity).apply {
                text = listOf(a.source, when_).filter { it.isNotEmpty() }.joinToString(" • ")
                textSize = 12f
                alpha = 0.6f
                setPadding(0, dp(2), 0, dp(4))
            })
            if (a.summary.isNotEmpty()) {
                box.addView(TextView(this@MainActivity).apply {
                    text = a.summary
                    textSize = 14f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            return box
        }
    }
}
