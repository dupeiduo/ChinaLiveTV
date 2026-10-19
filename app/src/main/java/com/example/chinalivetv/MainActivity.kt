package com.example.chinalivetv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class Channel(
    val name: String,
    val category: String,
    val url: String,
    val note: String = "",
    // Fallback lines for the same channel; tried in order when playback fails.
    val alts: List<String> = emptyList()
) {
    val allUrls: List<String> get() = listOf(url) + alts
}
// Many home routers hand out IPv6-capable DNS answers while the TV itself has no
// IPv6 route, which makes the platform network stack fail with ENETUNREACH.
// Prefer A (IPv4) records and only fall back to whatever the system returns.
class Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        return if (v4.isNotEmpty()) v4 else all
    }
}

class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var channelList: ListView
    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<Channel>
    private val channels = mutableListOf<Channel>()
    private var currentIndex = 0
    private var currentLine = 0
    private val io = Executors.newSingleThreadExecutor()

    // Community-maintained public playlists. Every reachable source is fetched
    // and merged so a dead mirror does not empty the list; each channel keeps
    // several candidate lines and falls through to the next one on error.
    private val playlistUrls = listOf(
        "https://cdn.jsdelivr.net/gh/best-fan/iptv-sources@main/cn_all_status.m3u8",
        "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all_status.m3u8",
        "https://iptv-org.github.io/iptv/countries/cn.m3u",
        "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/iptv/countries/cn.m3u"
    )

    // Shared client with IPv4-first DNS, used to download the playlists.
    // (Playback itself uses ExoPlayer's own data source; almost every stream URL
    // is a bare IP there, so DNS preference does not matter for playback.)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns())
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        channels.addAll(loadLocalChannels())
        enableTls12OnLegacyDevices()
        buildUi()
        setupPlayer()
        loadRemotePlaylist()
    }

    private fun loadLocalChannels(): List<Channel> = try {
        val raw = assets.open("channels.json").bufferedReader().use { it.readText() }
        val json = JSONArray(raw)
        (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val primary = o.optString("url").ifBlank { o.optJSONArray("urls")?.optString(0).orEmpty() }
            val extras = (0 until (o.optJSONArray("urls")?.length() ?: 0))
                .map { o.optJSONArray("urls")!!.getString(it) }
                .filter { it != primary }
            Channel(o.optString("name"), o.optString("category", "其他"), primary, o.optString("note"), extras)
        }.filter { it.url.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(13, 18, 30)); setPadding(18, 12, 18, 12)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 14, 0) }
        val title = TextView(this).apply {
            text = "中国电视直播"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); setPadding(10, 8, 10, 14)
        }
        left.addView(title)
        channelList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(48, 58, 75)); dividerHeight = 1
            selector = android.graphics.drawable.ColorDrawable(Color.rgb(45, 75, 120))
            isFocusable = true; isFocusableInTouchMode = true
        }
        adapter = object : ArrayAdapter<Channel>(this, android.R.layout.simple_list_item_1, channels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    setPadding(18, 14, 12, 14); textSize = 18f; isFocusable = false
                }
                val c = getItem(position)!!
                tv.text = "${c.category}  ·  ${c.name}"
                tv.setTextColor(Color.WHITE); tv.setBackgroundColor(Color.TRANSPARENT)
                return tv
            }
        }
        channelList.adapter = adapter
        channelList.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ -> playChannel(pos) }
        left.addView(channelList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playerView = PlayerView(this).apply {
            useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK); isFocusable = true
        }
        right.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "正在加载公开频道列表…\n直播源由第三方维护，可能失效或存在地区限制。"
            textSize = 16f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 12, 10, 8)
        }
        right.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.36f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.64f))
        setContentView(root)

        channelList.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { playerView.requestFocus(); true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { playChannel((currentIndex - 1).coerceAtLeast(0)); true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { playChannel((currentIndex + 1).coerceAtMost(channels.lastIndex)); true }
                else -> false
            }
        }
        playerView.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                channelList.requestFocus(); true
            } else false
        }
    }

    // Android 4.4 supports TLS 1.2 but leaves it disabled by default, which breaks
    // HTTPS streams served only over TLS 1.2. Keep platform ciphers, add TLS 1.2.
    private fun enableTls12OnLegacyDevices() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) return
        try {
            val delegate = SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
            HttpsURLConnection.setDefaultSSLSocketFactory(object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
                override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
                    withTls12(delegate.createSocket(s, host, port, autoClose))
                override fun createSocket(host: String, port: Int): Socket =
                    withTls12(delegate.createSocket(host, port))
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                    withTls12(delegate.createSocket(host, port, localHost, localPort))
                override fun createSocket(host: InetAddress, port: Int): Socket =
                    withTls12(delegate.createSocket(host, port))
                override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
                    withTls12(delegate.createSocket(host, port, localHost, localPort))
            })
        } catch (_: Exception) {
        }
    }

    private fun withTls12(socket: Socket): Socket {
        if (socket is SSLSocket && socket.supportedProtocols.contains("TLSv1.2")) {
            socket.enabledProtocols = (socket.enabledProtocols + "TLSv1.2").distinct().toTypedArray()
        }
        return socket
    }

    private fun setupPlayer() {
        // Stream URLs are mostly bare-IP HLS endpoints, so the platform default
        // data source is fine here; playlists use OkHttp (see loadRemotePlaylist).
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ChinaLiveTV/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
        player = ExoPlayer.Builder(this)
            .setDataSourceFactory(dataSourceFactory)
            .build().also { exo ->
            // Old TV hardware chokes on the highest rendition; prefer 720p max.
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(1280, 720).build()
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Automatically move to the next mirror line for this channel.
                    val c = channels.getOrNull(currentIndex)
                    val next = if (c != null && currentLine + 1 < c.allUrls.size) currentLine + 1 else -1
                    if (next >= 0) {
                        runOnUiThread { playChannel(currentIndex, next) }
                    } else {
                        status.text = "播放失败：${error.errorCodeName}。该频道已无可用线路，试试其他频道。"
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_BUFFERING) status.text = "正在连接直播源…"
                    if (state == Player.STATE_READY) status.text =
                        "正在播放：${channels.getOrNull(currentIndex)?.name.orEmpty()}\n频道源可用性和授权范围以源站为准。"
                }
            })
        }
    }

    // Fetch every reachable playlist and merge them; channels keep their spare
    // lines so playback can fall through to a mirror when one stream is dead.
    private fun loadRemotePlaylist() {
        io.execute {
            val errors = mutableListOf<String>()
            val fetched = mutableListOf<List<Channel>>()
            for ((idx, url) in playlistUrls.withIndex()) {
                try {
                    runOnUiThread { status.text = "正在加载频道列表（源 ${idx + 1}/${playlistUrls.size}）…" }
                    val body = http.newCall(
                        Request.Builder().url(url)
                            .header("User-Agent", "ChinaLiveTV/1.0").build()
                    ).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code()}")
                        resp.body()?.string() ?: error("空响应")
                    }
                    val parsed = parseM3u(body)
                    if (parsed.isNotEmpty()) fetched.add(parsed) else errors.add("源${idx + 1}: 空")
                } catch (e: Exception) {
                    errors.add("源${idx + 1}: ${e.message ?: "网络错误"}")
                }
            }
            val local = try { loadLocalChannels() } catch (_: Exception) { emptyList<Channel>() }
            val merged = mergeChannels(fetched + listOf(local))
            runOnUiThread {
                if (merged.isNotEmpty()) {
                    channels.clear(); channels.addAll(merged); adapter.notifyDataSetChanged()
                    status.text = "共 ${merged.size} 个频道（${fetched.size}/${playlistUrls.size} 个在线源）" +
                        if (errors.isEmpty()) "" else "。部分源失败：${errors.joinToString("；")}"
                } else status.text = "在线频道列表加载失败（${errors.joinToString("；")}）。请检查网络。"
            }
        }
    }

    // Group by channel name: first occurrence sets the primary line, every extra
    // URL for that name becomes a fallback (capped to avoid huge lists).
    private fun mergeChannels(lists: List<List<Channel>>): List<Channel> {
        val map = LinkedHashMap<String, Channel>()
        for (list in lists) for (c in list) {
            if (c.url.isBlank()) continue
            val key = c.name
            val prev = map[key]
            if (prev == null) {
                map[key] = c
            } else {
                val lines = (prev.allUrls + c.url + c.alts).distinct().take(4)
                map[key] = prev.copy(alts = lines.drop(1))
            }
        }
        return map.values.toList()
    }

    private fun parseM3u(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var name = ""
        var category = "其他"
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(",", "").trim().ifBlank { "未命名频道" }
                val group = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1).orEmpty()
                category = group.ifBlank {
                    when {
                        name.contains("CCTV", true) || name.contains("央视") -> "央视"
                        name.contains("卫视") || listOf("湖南", "浙江", "江苏", "东方", "北京", "广东", "山东", "河南", "安徽", "深圳").any { name.contains(it) } -> "卫视/地方台"
                        else -> "其他"
                    }
                }
            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                if (name.isNotBlank()) result.add(Channel(name, category, line))
                name = ""; category = "其他"
            }
        }
        return result.distinctBy { it.name + "|" + it.url }
    }

    private fun playChannel(index: Int, line: Int = 0) {
        if (index !in channels.indices) return
        currentIndex = index
        currentLine = line
        val c = channels[index]
        val urls = c.allUrls
        if (urls.isEmpty()) {
            status.text = "${c.name}：没有配置可播放地址。"; return
        }
        if (line !in urls.indices) return
        status.text = "正在连接：${c.name}" +
            if (urls.size > 1) "（线路 ${line + 1}/${urls.size}）" else ""
        player?.setMediaItem(MediaItem.fromUri(urls[line]))
        player?.prepare()
        player?.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (channels.isNotEmpty()) playChannel((currentIndex - 1).coerceAtLeast(0)); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (channels.isNotEmpty()) playChannel((currentIndex + 1).coerceAtMost(channels.lastIndex)); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() {
        playerView.player = null; player?.release(); player = null; io.shutdownNow(); super.onDestroy()
    }
}
