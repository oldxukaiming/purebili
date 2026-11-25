package com.noad.bilibili

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DlnaDevice(
    val name: String,
    val host: String,
    val location: String,
    val controlUrl: String
)

class DlnaCaster(
    private val context: Context,
    private val http: OkHttpClient
) {
    private var serverSocket: ServerSocket? = null
    private var proxyPort: Int = 0
    @Volatile
    private var activeToken: String = ""
    @Volatile
    private var activeTargetUrl: String = ""
    private val executor = Executors.newCachedThreadPool()

    suspend fun discoverDevices(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("purebili_dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }

        val locations = LinkedHashSet<String>()
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 350
            socket.broadcast = true
            val group = InetAddress.getByName("239.255.255.250")
            val targets = listOf(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1"
            )
            for (st in targets) {
                val msg = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(msg, msg.size, group, 1900))
            }

            val deadline = System.currentTimeMillis() + 2200L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    text.lineSequence().forEach { line ->
                        if (line.startsWith("LOCATION:", ignoreCase = true)) {
                            val loc = line.substringAfter(':').trim()
                            if (loc.isNotEmpty()) locations.add(loc)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            socket.close()
        } catch (_: Exception) {
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Exception) {
            }
        }

        val devices = mutableListOf<DlnaDevice>()
        val seenControl = HashSet<String>()
        for (loc in locations) {
            try {
                val req = Request.Builder().url(loc).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val xml = resp.body?.string() ?: return@use
                    parseDeviceXml(xml, loc)?.let { dev ->
                        if (seenControl.add(dev.controlUrl)) {
                            devices.add(dev)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        devices
    }

    suspend fun castVideo(device: DlnaDevice, mp4Url: String, title: String): String =
        withContext(Dispatchers.IO) {
            ensureCastProxyServer()
            val token = UUID.randomUUID().toString().replace("-", "")
            activeToken = token
            activeTargetUrl = mp4Url

            val localIp = getLocalIpForTarget(device.host)
            val castUrl = "http://$localIp:$proxyPort/cast/$token.mp4"

            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>${xmlEscape(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:video/mp4:*\">${xmlEscape(castUrl)}</res>" +
                "</item></DIDL-Lite>"

            sendSoap(
                device.controlUrl,
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>${xmlEscape(castUrl)}</CurrentURI>" +
                    "<CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>"
            )
            sendSoap(
                device.controlUrl,
                "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>"
            )
            castUrl
        }

    suspend fun control(device: DlnaDevice, command: String) = withContext(Dispatchers.IO) {
        when (command) {
            "play" -> sendSoap(device.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            "pause" -> sendSoap(device.controlUrl, "Pause", "<InstanceID>0</InstanceID>")
            "stop" -> {
                sendSoap(device.controlUrl, "Stop", "<InstanceID>0</InstanceID>")
                activeToken = ""
                activeTargetUrl = ""
            }
        }
    }

    private fun sendSoap(controlUrl: String, action: String, innerXml: String) {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      $innerXml
    </u:$action>
  </s:Body>
</s:Envelope>"""
        val mediaType = "text/xml; charset=\"utf-8\"".toMediaType()
        val req = Request.Builder()
            .url(controlUrl)
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .post(envelope.toRequestBody(mediaType))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("DLNA $action 失败 (HTTP ${resp.code})")
            }
        }
    }

    @Synchronized
    private fun ensureCastProxyServer() {
        if (serverSocket != null && proxyPort > 0) return
        val ss = ServerSocket(0)
        serverSocket = ss
        proxyPort = ss.localPort
        executor.execute {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    executor.execute { handleProxyClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleProxyClient(socket: Socket) {
        socket.use { sock ->
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                val out = sock.getOutputStream()
                val token = activeToken
                val target = activeTargetUrl
                if (token.isEmpty() || target.isEmpty() || path != "/cast/$token.mp4") {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                val streamClient = http.newBuilder()
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build()
                val reqBuilder = Request.Builder()
                    .url(target)
                    .header("User-Agent", BiliApi.UA)
                    .header("Referer", BiliApi.REFERER)
                    .header("Origin", "https://www.bilibili.com")
                if (rangeHeader != null) {
                    reqBuilder.header("Range", rangeHeader)
                }

                streamClient.newCall(reqBuilder.build()).execute().use { up ->
                    val statusMsg = if (up.code == 206) "Partial Content" else "OK"
                    val sb = StringBuilder()
                    sb.append("HTTP/1.1 ${up.code} $statusMsg\r\n")
                    sb.append("Content-Type: ${up.header("Content-Type") ?: "video/mp4"}\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                    up.header("Content-Length")?.let { sb.append("Content-Length: $it\r\n") }
                    up.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
                    sb.append("Connection: close\r\n\r\n")
                    out.write(sb.toString().toByteArray())
                    if (method != "HEAD") {
                        up.body?.byteStream()?.copyTo(out, 32 * 1024)
                    }
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun shutdown() {
        activeToken = ""
        activeTargetUrl = ""
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        proxyPort = 0
    }

    private fun getLocalIpForTarget(host: String): String {
        return try {
            DatagramSocket().use { sock ->
                sock.connect(InetAddress.getByName(host), 1900)
                sock.localAddress.hostAddress ?: "127.0.0.1"
            }
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun extractTag(xml: String, tag: String): String? {
        val regex = Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseDeviceXml(xml: String, location: String): DlnaDevice? {
        val name = extractTag(xml, "friendlyName") ?: "DLNA 电视设备"
        val base = extractTag(xml, "URLBase") ?: location
        val serviceRegex = Regex("<service>([\\s\\S]*?)</service>", RegexOption.IGNORE_CASE)
        var controlPath: String? = null
        for (match in serviceRegex.findAll(xml)) {
            val block = match.groupValues[1]
            if (block.contains("AVTransport")) {
                controlPath = extractTag(block, "controlURL")
                if (!controlPath.isNullOrEmpty()) break
            }
        }
        val ctrl = controlPath ?: return null
        val fullCtrl = URL(URL(base), ctrl).toString()
        val host = URL(fullCtrl).host
        return DlnaDevice(name, host, location, fullCtrl)
    }

    private fun xmlEscape(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
