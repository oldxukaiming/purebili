package com.noad.bilibili

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DlnaRendererServer(private val context: Context) {

    interface PlaybackListener {
        fun onCastPlayControl()
        fun onCastPauseControl()
        fun onCastStopControl()
        fun onCastSeekControl(positionMs: Long)
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    private var httpServer: ServerSocket? = null
    private var httpPort: Int = 0
    private var ssdpSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val prefs = context.getSharedPreferences("purebili_dlna_rx", Context.MODE_PRIVATE)
    val deviceUuid: String = prefs.getString("udn_uuid", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("udn_uuid", it).apply()
    }
    val friendlyName: String = "PureBili (${Build.MODEL})"

    @Volatile
    var currentUri: String = ""
    @Volatile
    var currentTitle: String = "投屏视频"
    @Volatile
    var transportState: String = "STOPPED" // PLAYING | PAUSED_PLAYBACK | STOPPED | TRANSITIONING
    @Volatile
    var positionMs: Long = 0L
    @Volatile
    var durationMs: Long = 0L

    @Volatile
    var listener: PlaybackListener? = null

    fun isEnabled(): Boolean {
        val defaultOn = PureBiliApp.instance.isTvMode()
        return prefs.getBoolean("receiver_enabled", defaultOn)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("receiver_enabled", enabled).apply()
        if (enabled) start() else stop()
    }

    fun isRunning(): Boolean = running.get()

    @Synchronized
    fun start() {
        if (running.get()) return
        running.set(true)
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("purebili_dlna_renderer")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        try {
            val ss = try {
                ServerSocket(49199)
            } catch (_: Exception) {
                ServerSocket(0)
            }
            httpServer = ss
            httpPort = ss.localPort
            executor.execute { acceptHttpLoop(ss) }
            executor.execute { ssdpListenLoop() }
            executor.execute { ssdpNotifyLoop() }
        } catch (_: Exception) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            sendNotifyAll("ssdp:byebye")
        } catch (_: Exception) {
        }
        try {
            httpServer?.close()
        } catch (_: Exception) {
        }
        httpServer = null
        httpPort = 0
        try {
            ssdpSocket?.close()
        } catch (_: Exception) {
        }
        ssdpSocket = null
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun acceptHttpLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                executor.execute { handleHttpClient(client) }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 8000
                val input = BufferedInputStream(sock.getInputStream())
                val headerBytes = ByteArrayOutputStream()
                var prev3 = 0
                var prev2 = 0
                var prev1 = 0
                while (true) {
                    val b = input.read()
                    if (b == -1) return
                    headerBytes.write(b)
                    if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                        break
                    }
                    prev3 = prev2
                    prev2 = prev1
                    prev1 = b
                    if (headerBytes.size() > 32768) return
                }

                val headerText = headerBytes.toString("UTF-8")
                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull() ?: return
                val reqParts = requestLine.split(" ")
                if (reqParts.size < 2) return
                val method = reqParts[0].uppercase()
                val path = reqParts[1].substringBefore('?')

                val headers = HashMap<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                var body = ""
                if (contentLength in 1..262144) {
                    val buf = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = input.read(buf, readTotal, contentLength - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    body = String(buf, 0, readTotal, Charsets.UTF_8)
                }

                val localHostHeader = headers["host"]?.substringBefore(':')
                val hostIp = localHostHeader?.takeIf { it.isNotEmpty() && it != "127.0.0.1" }
                    ?: getLanIpv4Address()

                val out = sock.getOutputStream()
                if (method == "SUBSCRIBE" || method == "UNSUBSCRIBE") {
                    val sid = headers["sid"] ?: "uuid:${UUID.randomUUID()}"
                    val resp = "HTTP/1.1 200 OK\r\nSID: $sid\r\nTIMEOUT: Second-1800\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(resp.toByteArray(Charsets.UTF_8))
                    out.flush()
                    return
                }

                when (path) {
                    "/description.xml" -> writeHttpResponse(
                        out,
                        200,
                        "text/xml; charset=utf-8",
                        buildDescriptionXml(hostIp)
                    )
                    "/avtransport.xml" -> writeHttpResponse(
                        out,
                        200,
                        "text/xml; charset=utf-8",
                        AV_TRANSPORT_SCPD
                    )
                    "/renderingcontrol.xml" -> writeHttpResponse(
                        out,
                        200,
                        "text/xml; charset=utf-8",
                        RENDERING_CONTROL_SCPD
                    )
                    "/connectionmanager.xml" -> writeHttpResponse(
                        out,
                        200,
                        "text/xml; charset=utf-8",
                        CONNECTION_MANAGER_SCPD
                    )
                    "/control/AVTransport" -> {
                        val soapAction = (headers["soapaction"] ?: "").trim('"')
                        val respXml = handleAvTransportSoap(soapAction, body)
                        writeHttpResponse(out, 200, "text/xml; charset=utf-8", respXml)
                    }
                    "/control/RenderingControl" -> {
                        val soapAction = (headers["soapaction"] ?: "").trim('"')
                        val respXml = handleRenderingControlSoap(soapAction, body)
                        writeHttpResponse(out, 200, "text/xml; charset=utf-8", respXml)
                    }
                    "/control/ConnectionManager" -> {
                        val respXml = handleConnectionManagerSoap()
                        writeHttpResponse(out, 200, "text/xml; charset=utf-8", respXml)
                    }
                    else -> writeHttpResponse(out, 404, "text/plain", "Not Found")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun writeHttpResponse(
        out: java.io.OutputStream,
        code: Int,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "Not Found"
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "EXT:\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun handleAvTransportSoap(soapAction: String, body: String): String {
        val action = soapAction.substringAfter('#').ifEmpty {
            Regex("<u:([A-Za-z0-9_]+)").find(body)?.groupValues?.getOrNull(1) ?: ""
        }
        when (action) {
            "SetAVTransportURI" -> {
                val rawUri = extractXmlTag(body, "CurrentURI").orEmpty()
                val uri = xmlUnescape(rawUri).trim()
                val metaRaw = xmlUnescape(extractXmlTag(body, "CurrentURIMetaData").orEmpty())
                val title = xmlUnescape(extractXmlTag(metaRaw, "dc:title").orEmpty())
                    .ifEmpty { "手机投屏视频" }

                if (uri.isNotEmpty()) {
                    currentUri = uri
                    currentTitle = title
                    transportState = "TRANSITIONING"
                    positionMs = 0L
                    durationMs = 0L
                    launchOrUpdatePlayer(uri, title)
                }
                return wrapSoapResponse("AVTransport", "SetAVTransportURI", "")
            }
            "Play" -> {
                if (listener != null) {
                    listener?.onCastPlayControl()
                } else if (currentUri.isNotEmpty()) {
                    launchOrUpdatePlayer(currentUri, currentTitle)
                }
                transportState = "PLAYING"
                return wrapSoapResponse("AVTransport", "Play", "")
            }
            "Pause" -> {
                listener?.onCastPauseControl()
                transportState = "PAUSED_PLAYBACK"
                return wrapSoapResponse("AVTransport", "Pause", "")
            }
            "Stop" -> {
                listener?.onCastStopControl()
                transportState = "STOPPED"
                return wrapSoapResponse("AVTransport", "Stop", "")
            }
            "Seek" -> {
                val target = extractXmlTag(body, "Target").orEmpty()
                val targetMs = parseHmsToMs(target)
                if (targetMs >= 0L) {
                    positionMs = targetMs
                    listener?.onCastSeekControl(targetMs)
                }
                return wrapSoapResponse("AVTransport", "Seek", "")
            }
            "GetTransportInfo" -> {
                return wrapSoapResponse(
                    "AVTransport",
                    "GetTransportInfo",
                    "<CurrentTransportState>$transportState</CurrentTransportState>" +
                        "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
                        "<CurrentSpeed>1</CurrentSpeed>"
                )
            }
            "GetPositionInfo" -> {
                val durStr = formatMsToHms(durationMs)
                val posStr = formatMsToHms(positionMs)
                return wrapSoapResponse(
                    "AVTransport",
                    "GetPositionInfo",
                    "<Track>1</Track>" +
                        "<TrackDuration>$durStr</TrackDuration>" +
                        "<TrackMetaData></TrackMetaData>" +
                        "<TrackURI>${xmlEscape(currentUri)}</TrackURI>" +
                        "<RelTime>$posStr</RelTime>" +
                        "<AbsTime>$posStr</AbsTime>" +
                        "<RelCount>2147483647</RelCount>" +
                        "<AbsCount>2147483647</AbsCount>"
                )
            }
            "GetMediaInfo" -> {
                return wrapSoapResponse(
                    "AVTransport",
                    "GetMediaInfo",
                    "<NrTracks>1</NrTracks>" +
                        "<MediaDuration>${formatMsToHms(durationMs)}</MediaDuration>" +
                        "<CurrentURI>${xmlEscape(currentUri)}</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>" +
                        "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>" +
                        "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
                        "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>"
                )
            }
            else -> return wrapSoapResponse("AVTransport", action.ifEmpty { "Unknown" }, "")
        }
    }

    private fun handleRenderingControlSoap(soapAction: String, body: String): String {
        val action = soapAction.substringAfter('#')
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVol = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
        return when (action) {
            "GetVolume" -> {
                val cur = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 8
                val pct = (cur * 100) / maxVol
                wrapSoapResponse("RenderingControl", "GetVolume", "<CurrentVolume>$pct</CurrentVolume>")
            }
            "SetVolume" -> {
                val pct = extractXmlTag(body, "DesiredVolume")?.toIntOrNull()?.coerceIn(0, 100)
                if (pct != null) {
                    val targetVol = (pct * maxVol) / 100
                    audio?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                }
                wrapSoapResponse("RenderingControl", "SetVolume", "")
            }
            "GetMute" -> wrapSoapResponse("RenderingControl", "GetMute", "<CurrentMute>0</CurrentMute>")
            else -> wrapSoapResponse("RenderingControl", action.ifEmpty { "SetMute" }, "")
        }
    }

    private fun handleConnectionManagerSoap(): String {
        val sink = "http-get:*:video/mp4:*,http-get:*:video/x-flv:*," +
            "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*," +
            "http-get:*:video/x-matroska:*,http-get:*:*:*"
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
      <Source></Source>
      <Sink>$sink</Sink>
    </u:GetProtocolInfoResponse>
  </s:Body>
</s:Envelope>"""
    }

    private fun launchOrUpdatePlayer(uri: String, title: String) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PlayerActivity.EXTRA_CAST_URL, uri)
            putExtra(PlayerActivity.EXTRA_CAST_TITLE, title)
        }
        context.startActivity(intent)
    }

    private fun ssdpListenLoop() {
        try {
            val ms = MulticastSocket(null)
            ms.reuseAddress = true
            ms.bind(InetSocketAddress(1900))
            val group = InetAddress.getByName("239.255.255.250")
            try {
                ms.joinGroup(group)
            } catch (_: Exception) {
            }
            ssdpSocket = ms
            val buf = ByteArray(4096)
            while (running.get() && !ms.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                ms.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (text.startsWith("M-SEARCH", ignoreCase = true)) {
                    val st = text.lineSequence()
                        .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?: "ssdp:all"
                    respondToSearch(ms, packet.address, packet.port, st)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun ssdpNotifyLoop() {
        while (running.get()) {
            try {
                sendNotifyAll("ssdp:alive")
                Thread.sleep(25_000L)
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
            }
        }
    }

    private fun respondToSearch(
        socket: DatagramSocket,
        addr: InetAddress,
        port: Int,
        st: String
    ) {
        val ip = getLanIpv4Address()
        val location = "http://$ip:$httpPort/description.xml"
        val targets = when {
            st.equals("ssdp:all", ignoreCase = true) -> listOf(
                "upnp:rootdevice",
                "uuid:$deviceUuid",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1"
            )
            st == "upnp:rootdevice" ||
                st == "uuid:$deviceUuid" ||
                st.equals("urn:schemas-upnp-org:device:MediaRenderer:1", true) ||
                st.equals("urn:schemas-upnp-org:service:AVTransport:1", true) ||
                st.equals("urn:schemas-upnp-org:service:RenderingControl:1", true) -> listOf(st)
            else -> emptyList()
        }
        for (target in targets) {
            val usn = if (target == "uuid:$deviceUuid") target else "uuid:$deviceUuid::$target"
            val msg = "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: $location\r\n" +
                "SERVER: Linux/3.10 UPnP/1.0 PureBili/1.0\r\n" +
                "ST: $target\r\n" +
                "USN: $usn\r\n\r\n"
            val bytes = msg.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, addr, port))
        }
    }

    private fun sendNotifyAll(nts: String) {
        if (httpPort <= 0) return
        val ip = getLanIpv4Address()
        val location = "http://$ip:$httpPort/description.xml"
        val group = InetAddress.getByName("239.255.255.250")
        DatagramSocket().use { sock ->
            val targets = listOf(
                "upnp:rootdevice",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1"
            )
            for (nt in targets) {
                val msg = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: $location\r\n" +
                    "NT: $nt\r\n" +
                    "NTS: $nts\r\n" +
                    "SERVER: Linux/3.10 UPnP/1.0 PureBili/1.0\r\n" +
                    "USN: uuid:$deviceUuid::$nt\r\n\r\n"
                val bytes = msg.toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(bytes, bytes.size, group, 1900))
            }
        }
    }

    private fun buildDescriptionXml(ip: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <URLBase>http://$ip:$httpPort</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>${xmlEscape(friendlyName)}</friendlyName>
    <manufacturer>PureBili</manufacturer>
    <modelName>PureBili TV Renderer</modelName>
    <UDN>uuid:$deviceUuid</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/avtransport.xml</SCPDURL>
        <controlURL>/control/AVTransport</controlURL>
        <eventSubURL>/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/renderingcontrol.xml</SCPDURL>
        <controlURL>/control/RenderingControl</controlURL>
        <eventSubURL>/event/RenderingControl</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/connectionmanager.xml</SCPDURL>
        <controlURL>/control/ConnectionManager</controlURL>
        <eventSubURL>/event/ConnectionManager</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    private fun wrapSoapResponse(service: String, action: String, inner: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">
      $inner
    </u:${action}Response>
  </s:Body>
</s:Envelope>"""
    }

    private fun getLanIpv4Address(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    companion object {
        fun extractXmlTag(xml: String, tag: String): String? {
            val regex = Regex("<(?:[a-zA-Z0-9_]+:)?${Regex.escape(tag.substringAfter(':'))}[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_]+:)?${Regex.escape(tag.substringAfter(':'))}>", RegexOption.IGNORE_CASE)
            return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
        }

        fun xmlEscape(s: String): String {
            return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }

        fun xmlUnescape(s: String): String {
            return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
        }

        fun formatMsToHms(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val h = totalSec / 3600L
            val m = (totalSec % 3600L) / 60L
            val s = totalSec % 60L
            return "%02d:%02d:%02d".format(h, m, s)
        }

        fun parseHmsToMs(hms: String): Long {
            val parts = hms.trim().split(":")
            if (parts.size != 3) return -1L
            val h = parts[0].toLongOrNull() ?: return -1L
            val m = parts[1].toLongOrNull() ?: return -1L
            val s = parts[2].substringBefore('.').toLongOrNull() ?: return -1L
            return (h * 3600L + m * 60L + s) * 1000L
        }

        private const val AV_TRANSPORT_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name></action>
    <action><name>Play</name></action>
    <action><name>Pause</name></action>
    <action><name>Stop</name></action>
    <action><name>Seek</name></action>
    <action><name>GetTransportInfo</name></action>
    <action><name>GetPositionInfo</name></action>
    <action><name>GetMediaInfo</name></action>
  </actionList>
  <serviceStateTable></serviceStateTable>
</scpd>"""

        private const val RENDERING_CONTROL_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name></action>
    <action><name>SetVolume</name></action>
    <action><name>GetMute</name></action>
    <action><name>SetMute</name></action>
  </actionList>
  <serviceStateTable></serviceStateTable>
</scpd>"""

        private const val CONNECTION_MANAGER_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name></action>
  </actionList>
  <serviceStateTable></serviceStateTable>
</scpd>"""
    }
}
