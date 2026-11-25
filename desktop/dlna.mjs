import dgram from "node:dgram";
import http from "node:http";
import crypto from "node:crypto";
import { Readable } from "node:stream";

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
const REFERER = "https://www.bilibili.com/";

let castProxyServer = null;
let castProxyPort = 0;
let activeCastToken = "";
let activeCastTargetUrl = "";

export function xmlEscape(str) {
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

export function extractTagValue(xml, tagName) {
  const re = new RegExp(`<${tagName}[^>]*>([\\s\\S]*?)</${tagName}>`, "i");
  const m = xml.match(re);
  return m ? m[1].trim() : "";
}

export function parseDlnaDeviceXml(xml, locationUrl) {
  const friendlyName = extractTagValue(xml, "friendlyName") || "DLNA 电视设备";
  const urlBase = extractTagValue(xml, "URLBase") || locationUrl;

  const serviceMatches = xml.match(/<service>[\s\S]*?<\/service>/gi) || [];
  let controlPath = "";
  for (const block of serviceMatches) {
    if (block.includes("AVTransport")) {
      controlPath = extractTagValue(block, "controlURL");
      if (controlPath) break;
    }
  }
  if (!controlPath) return null;

  const controlUrl = new URL(controlPath, urlBase).toString();
  const parsed = new URL(controlUrl);
  return {
    id: locationUrl,
    name: friendlyName,
    host: parsed.hostname,
    location: locationUrl,
    control_url: controlUrl,
  };
}

export function getLocalIpForTarget(targetHost) {
  return new Promise((resolve) => {
    const sock = dgram.createSocket("udp4");
    const fallback = "127.0.0.1";
    try {
      sock.connect(1900, targetHost, () => {
        try {
          const addr = sock.address();
          sock.close();
          resolve(addr.address || fallback);
        } catch {
          try {
            sock.close();
          } catch {}
          resolve(fallback);
        }
      });
      sock.on("error", () => {
        try {
          sock.close();
        } catch {}
        resolve(fallback);
      });
    } catch {
      try {
        sock.close();
      } catch {}
      resolve(fallback);
    }
  });
}

export async function discoverDlnaDevices(timeoutMs = 2200) {
  const locations = new Set();
  await new Promise((resolve) => {
    const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
    const searchTargets = [
      "urn:schemas-upnp-org:service:AVTransport:1",
      "urn:schemas-upnp-org:device:MediaRenderer:1",
    ];

    socket.on("message", (msg) => {
      const text = msg.toString("utf8");
      const match = text.match(/^LOCATION:\s*(.+)$/im);
      if (match && match[1]) {
        locations.add(match[1].trim());
      }
    });

    socket.on("error", () => {
      try {
        socket.close();
      } catch {}
      resolve();
    });

    socket.bind(() => {
      try {
        socket.setBroadcast(true);
      } catch {}
      for (const st of searchTargets) {
        const packet = Buffer.from(
          [
            "M-SEARCH * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            'MAN: "ssdp:discover"',
            "MX: 2",
            `ST: ${st}`,
            "",
            "",
          ].join("\r\n"),
          "utf8"
        );
        socket.send(packet, 1900, "239.255.255.250");
      }
      setTimeout(() => {
        try {
          socket.close();
        } catch {}
        resolve();
      }, timeoutMs);
    });
  });

  const devices = [];
  const seenControl = new Set();
  await Promise.all(
    [...locations].map(async (loc) => {
      try {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), 2000);
        const resp = await fetch(loc, { signal: ctrl.signal });
        clearTimeout(timer);
        if (!resp.ok) return;
        const xml = await resp.text();
        const parsed = parseDlnaDeviceXml(xml, loc);
        if (parsed && !seenControl.has(parsed.control_url)) {
          seenControl.add(parsed.control_url);
          devices.push(parsed);
        }
      } catch {}
    })
  );
  return devices;
}

export async function ensureCastProxyServer() {
  if (castProxyServer && castProxyPort > 0) {
    return castProxyPort;
  }
  castProxyServer = http.createServer(async (req, res) => {
    try {
      const u = new URL(req.url || "/", "http://127.0.0.1");
      if (
        !activeCastToken ||
        !activeCastTargetUrl ||
        u.pathname !== `/cast/${activeCastToken}.mp4`
      ) {
        res.writeHead(404);
        return res.end("Not found");
      }

      const headers = {
        "User-Agent": UA,
        Referer: REFERER,
        Origin: "https://www.bilibili.com",
      };
      if (req.headers.range) headers.Range = req.headers.range;

      const up = await fetch(activeCastTargetUrl, { headers });
      const respHeaders = {
        "Access-Control-Allow-Origin": "*",
        "Content-Type": up.headers.get("content-type") || "video/mp4",
      };
      for (const h of [
        "content-length",
        "content-range",
        "accept-ranges",
      ]) {
        const v = up.headers.get(h);
        if (v) respHeaders[h] = v;
      }
      if (!respHeaders["accept-ranges"]) {
        respHeaders["accept-ranges"] = "bytes";
      }
      res.writeHead(up.status, respHeaders);
      if (req.method === "HEAD") {
        return res.end();
      }
      if (up.body) {
        Readable.fromWeb(up.body).pipe(res);
      } else {
        res.end();
      }
    } catch (e) {
      res.writeHead(502);
      res.end(String(e));
    }
  });

  await new Promise((resolve) => {
    castProxyServer.listen(0, "0.0.0.0", () => {
      const addr = castProxyServer.address();
      castProxyPort = typeof addr === "object" && addr ? addr.port : 0;
      resolve();
    });
  });
  return castProxyPort;
}

export async function setActiveCastStream(mp4Url) {
  const port = await ensureCastProxyServer();
  activeCastToken = crypto.randomBytes(16).toString("hex");
  activeCastTargetUrl = mp4Url;
  return { port, token: activeCastToken };
}

export function clearActiveCastStream() {
  activeCastToken = "";
  activeCastTargetUrl = "";
}

export async function closeCastProxyServer() {
  clearActiveCastStream();
  if (castProxyServer) {
    await new Promise((resolve) => castProxyServer.close(resolve));
    castProxyServer = null;
    castProxyPort = 0;
  }
}

async function sendSoapAction(controlUrl, action, bodyInnerXml) {
  const envelope = `<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action} xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      ${bodyInnerXml}
    </u:${action}>
  </s:Body>
</s:Envelope>`;

  const resp = await fetch(controlUrl, {
    method: "POST",
    headers: {
      "Content-Type": 'text/xml; charset="utf-8"',
      SOAPAction: `"urn:schemas-upnp-org:service:AVTransport:1#${action}"`,
    },
    body: envelope,
  });
  if (!resp.ok) {
    const errText = await resp.text().catch(() => "");
    throw new Error(`DLNA ${action} 失败 (HTTP ${resp.status}): ${errText.slice(0, 160)}`);
  }
}

export async function castToDevice({ controlUrl, mp4Url, title }) {
  const parsed = new URL(controlUrl);
  const { port, token } = await setActiveCastStream(mp4Url);
  const localIp = await getLocalIpForTarget(parsed.hostname);
  const castUrl = `http://${localIp}:${port}/cast/${token}.mp4`;

  const didl = `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${xmlEscape(title || "PureBili Video")}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mp4:*">${xmlEscape(castUrl)}</res></item></DIDL-Lite>`;

  await sendSoapAction(
    controlUrl,
    "SetAVTransportURI",
    `<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(castUrl)}</CurrentURI><CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>`
  );
  await sendSoapAction(
    controlUrl,
    "Play",
    `<InstanceID>0</InstanceID><Speed>1</Speed>`
  );
  return { cast_url: castUrl };
}

export async function controlDlnaDevice(controlUrl, command) {
  if (command === "play") {
    await sendSoapAction(controlUrl, "Play", `<InstanceID>0</InstanceID><Speed>1</Speed>`);
  } else if (command === "pause") {
    await sendSoapAction(controlUrl, "Pause", `<InstanceID>0</InstanceID>`);
  } else if (command === "stop") {
    await sendSoapAction(controlUrl, "Stop", `<InstanceID>0</InstanceID>`);
    clearActiveCastStream();
  } else {
    throw new Error(`Unsupported DLNA command: ${command}`);
  }
}
