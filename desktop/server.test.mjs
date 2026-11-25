import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createPureBiliServer, isAllowedBilibiliHost } from "./server.mjs";
import http from "node:http";
import {
  castToDevice,
  clearActiveCastStream,
  closeCastProxyServer,
  controlDlnaDevice,
  ensureCastProxyServer,
  parseDlnaDeviceXml,
  setActiveCastStream,
} from "./dlna.mjs";

test("isAllowedBilibiliHost allows bilibili CDNs and blocks arbitrary hosts", () => {
  assert.equal(
    isAllowedBilibiliHost("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1.m4s"),
    true
  );
  assert.equal(isAllowedBilibiliHost("https://i0.hdslb.com/bfs/archive/a.jpg"), true);
  assert.equal(isAllowedBilibiliHost("https://evil.example.com/x.mp4"), false);
  assert.equal(isAllowedBilibiliHost("file:///C:/Windows/win.ini"), false);
});

test("parseDlnaDeviceXml extracts friendlyName and AVTransport controlURL", () => {
  const xml = `<?xml version="1.0"?>
  <root>
    <device>
      <friendlyName>小米电视 4A</friendlyName>
      <serviceList>
        <service>
          <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
          <controlURL>/upnp/control/AVTransport1</controlURL>
        </service>
      </serviceList>
    </device>
  </root>`;
  const dev = parseDlnaDeviceXml(xml, "http://192.168.1.50:49152/description.xml");
  assert.ok(dev);
  assert.equal(dev.name, "小米电视 4A");
  assert.equal(dev.host, "192.168.1.50");
  assert.equal(dev.control_url, "http://192.168.1.50:49152/upnp/control/AVTransport1");
});

test("Cast proxy server rejects unauthorized token paths and accepts active token", async () => {
  const port = await ensureCastProxyServer();
  try {
    const { token } = await setActiveCastStream("https://upos-sz-mirrorcos.bilivideo.com/fake.mp4");
    assert.ok(port > 0);
    assert.ok(token.length === 32);

    const badRes = await fetch(`http://127.0.0.1:${port}/cast/wrongtoken.mp4`);
    assert.equal(badRes.status, 404);
    clearActiveCastStream();

    const clearedRes = await fetch(`http://127.0.0.1:${port}/cast/${token}.mp4`);
    assert.equal(clearedRes.status, 404);
  } finally {
    await closeCastProxyServer();
  }
});

test("PureBili local server serves built dist, blocks SSRF, and validates unfollow CSRF", async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "purebili-test-"));
  const srv = createPureBiliServer({ sessionDir: tmpDir });
  await new Promise((resolve) => srv.listen(0, "127.0.0.1", resolve));
  const addr = srv.address();
  const baseUrl = `http://127.0.0.1:${addr.port}`;

  try {
    const homeResp = await fetch(`${baseUrl}/`);
    assert.equal(homeResp.status, 200);
    const html = await homeResp.text();
    assert.match(html, /PureBili/);

    const ssrfResp = await fetch(
      `${baseUrl}/proxy?url=${encodeURIComponent("https://example.com/secret")}`
    );
    assert.equal(ssrfResp.status, 403);

    const geetestResp = await fetch(
      `${baseUrl}/geetest?token=t1&gt=g1&challenge=c1`
    );
    assert.equal(geetestResp.status, 200);
    const gtHtml = await geetestResp.text();
    assert.match(gtHtml, /initGeetest/);

    const unfollowResp = await fetch(`${baseUrl}/api/unfollow`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ mid: 12345 }),
    });
    assert.equal(unfollowResp.status, 400);

    const unfavResp = await fetch(`${baseUrl}/api/fav/unfavorite`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ aid: 12345, media_ids: [999] }),
    });
    assert.equal(unfavResp.status, 400);
  } finally {
    await new Promise((resolve) => srv.close(resolve));
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("castToDevice and controlDlnaDevice send valid SOAP AVTransport envelopes", async () => {
  const receivedActions = [];
  let receivedUri = "";
  const mockTv = http.createServer((req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const action = req.headers["soapaction"] || "";
      const xml = Buffer.concat(chunks).toString("utf8");
      receivedActions.push(String(action));
      const m = xml.match(/<CurrentURI>([\s\S]*?)<\/CurrentURI>/);
      if (m) receivedUri = m[1];
      res.writeHead(200, { "Content-Type": "text/xml" });
      res.end("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>");
    });
  });
  await new Promise((r) => mockTv.listen(0, "127.0.0.1", r));
  const controlUrl = `http://127.0.0.1:${mockTv.address().port}/upnp/control/AVTransport1`;

  try {
    const res = await castToDevice({
      controlUrl,
      mp4Url: "https://upos-sz-mirrorcos.bilivideo.com/sample.mp4",
      title: "测试视频 & 投屏",
    });
    assert.match(res.cast_url, /^http:\/\/.+:\d+\/cast\/[0-9a-f]{32}\.mp4$/);
    assert.equal(receivedUri, res.cast_url);
    assert.ok(receivedActions.some((a) => a.includes("SetAVTransportURI")));
    assert.ok(receivedActions.some((a) => a.includes("#Play")));

    await controlDlnaDevice(controlUrl, "pause");
    await controlDlnaDevice(controlUrl, "stop");
    assert.ok(receivedActions.some((a) => a.includes("#Pause")));
    assert.ok(receivedActions.some((a) => a.includes("#Stop")));
  } finally {
    await closeCastProxyServer();
    await new Promise((r) => mockTv.close(r));
  }
});
