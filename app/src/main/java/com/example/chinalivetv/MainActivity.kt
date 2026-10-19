package com.example.chinalivetv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
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
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class Channel(val name: String, val category: String, val url: String, val note: String = "")

class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var channelList: ListView
    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<Channel>
    private val channels = mutableListOf<Channel>()
    private var currentIndex = 0
    private val io = Executors.newSingleThreadExecutor()

    // Community-maintained public playlist. Availability and usage rights vary by stream.
    private val playlistUrl = "https://iptv-org.github.io/iptv/countries/cn.m3u"

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
            Channel(o.optString("name"), o.optString("category", "其他"), o.optString("url"), o.optString("note"))
        }
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

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    status.text = "播放失败：${error.errorCodeName}。可尝试其他频道或刷新列表。"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_BUFFERING) status.text = "正在连接直播源…"
                    if (state == Player.STATE_READY) status.text =
                        "正在播放：${channels.getOrNull(currentIndex)?.name.orEmpty()}\n频道源可用性和授权范围以源站为准。"
                }
            })
        }
    }

    // Android 4.4 supports TLS 1.2 but does not enable it by default, which breaks
    // HTTPS playlists and streams. Keep the platform defaults and add TLS 1.2 on top.
    private fun enableTls12OnLegacyDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return
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

    private fun loadRemotePlaylist() {
        io.execute {
            try {
                val conn = (URL(playlistUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000; readTimeout = 15000
                    setRequestProperty("User-Agent", "ChinaLiveTV/1.0")
                }
                val body = try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally { conn.disconnect() }
                val parsed = parseM3u(body)
                runOnUiThread {
                    if (parsed.isNotEmpty()) {
                        channels.clear(); channels.addAll(parsed); adapter.notifyDataSetChanged()
                        status.text = "已加载 ${parsed.size} 个公开列表频道。请注意：第三方列表不保证每路信号均可播放或获授权。"
                    } else status.text = "在线列表中没有解析到频道。可检查网络或使用本地频道配置。"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "在线频道列表加载失败：${e.message ?: "网络错误"}。请检查网络。" }
            }
        }
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

    private fun playChannel(index: Int) {
        if (index !in channels.indices) return
        currentIndex = index
        val c = channels[index]
        if (c.url.isBlank()) {
            status.text = "${c.name}：没有配置可播放地址。"; return
        }
        status.text = "正在连接：${c.name}"
        player?.setMediaItem(MediaItem.fromUri(c.url))
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
