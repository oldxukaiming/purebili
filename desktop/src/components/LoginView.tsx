import React, { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { listen } from "@tauri-apps/api/event";
import { KeyRound, QrCode, RefreshCw, ShieldCheck } from "lucide-react";
import { api, isTauriEnv } from "../api";
import type { GeetestVerifyPayload } from "../types";

interface LoginViewProps {
  onSuccess: () => void;
}

export const LoginView: React.FC<LoginViewProps> = ({ onSuccess }) => {
  const [mode, setMode] = useState<"qr" | "password">("qr");

  // QR state
  const [qrDataUrl, setQrDataUrl] = useState("");
  const [qrcodeKey, setQrcodeKey] = useState("");
  const [qrStatus, setQrStatus] = useState("正在获取登录二维码...");
  const [qrExpired, setQrExpired] = useState(false);

  // Password state
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pwdStatus, setPwdStatus] = useState("");
  const [pwdLoading, setPwdLoading] = useState(false);
  const [verifyUrl, setVerifyUrl] = useState<string | null>(null);

  const credsRef = useRef({ username: "", password: "" });
  credsRef.current = { username, password };

  const loadQrCode = useCallback(async () => {
    setQrExpired(false);
    setQrStatus("正在生成二维码...");
    try {
      const info = await api.qrcodeGenerate();
      const dataUrl = await QRCode.toDataURL(info.url, {
        width: 220,
        margin: 2,
        color: { dark: "#111827", light: "#FFFFFF" },
      });
      setQrDataUrl(dataUrl);
      setQrcodeKey(info.qrcode_key);
      setQrStatus("请打开手机哔哩哔哩 App 扫码确认登录");
    } catch (err) {
      setQrStatus(`获取二维码失败：${String(err)}`);
    }
  }, []);

  useEffect(() => {
    if (mode === "qr") {
      void loadQrCode();
    }
  }, [mode, loadQrCode]);

  useEffect(() => {
    if (mode !== "qr" || !qrcodeKey || qrExpired) return;
    const timer = window.setInterval(async () => {
      try {
        const res = await api.qrcodePoll(qrcodeKey);
        if (res.logged_in) {
          window.clearInterval(timer);
          setQrStatus("登录成功，正在加载您的关注与收藏...");
          onSuccess();
        } else if (res.status === 86090) {
          setQrStatus("已扫码，请在手机上点击确认登录");
        } else if (res.status === 86038) {
          setQrExpired(true);
          setQrStatus("二维码已过期，请点击刷新");
          window.clearInterval(timer);
        }
      } catch {
        // Ignore transient poll errors
      }
    }, 2000);
    return () => window.clearInterval(timer);
  }, [mode, qrcodeKey, qrExpired, onSuccess]);

  // Listen for official Geetest verification completion from Tauri window or browser popup
  useEffect(() => {
    const handleVerified = async (payload: GeetestVerifyPayload) => {
      const { token, challenge, validate, seccode } = payload;
      setPwdStatus("安全验证通过，正在登录...");
      setPwdLoading(true);
      try {
        const res = await api.loginWithPassword({
          username: credsRef.current.username.trim(),
          password: credsRef.current.password,
          token,
          challenge,
          validate,
          seccode,
        });
        if (res.success) {
          setPwdStatus("登录成功！");
          onSuccess();
        } else {
          setPwdStatus(res.message);
          setVerifyUrl(res.verify_url);
        }
      } catch (err) {
        setPwdStatus(`登录异常：${String(err)}`);
      } finally {
        setPwdLoading(false);
      }
    };

    const onWindowMessage = (event: MessageEvent) => {
      if (event.data && event.data.type === "geetest-verified" && event.data.payload) {
        void handleVerified(event.data.payload as GeetestVerifyPayload);
      }
    };
    window.addEventListener("message", onWindowMessage);

    let unlisten: (() => void) | null = null;
    if (isTauriEnv()) {
      listen<GeetestVerifyPayload>("geetest-verified", (event) => {
        void handleVerified(event.payload);
      }).then((fn) => {
        unlisten = fn;
      });
    }

    return () => {
      window.removeEventListener("message", onWindowMessage);
      if (unlisten) unlisten();
    };
  }, [onSuccess]);

  const handlePasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password) {
      setPwdStatus("请输入手机号/邮箱与密码");
      return;
    }
    setVerifyUrl(null);
    setPwdLoading(true);
    setPwdStatus("正在请求官方人机验证...");
    try {
      const cap = await api.getPasswordCaptcha();
      setPwdStatus("请在弹出的官方安全验证窗口中完成滑块/点选验证");
      await api.openGeetestWindow(cap.token, cap.gt, cap.challenge);
    } catch (err) {
      setPwdStatus(`启动安全验证失败：${String(err)}`);
    } finally {
      setPwdLoading(false);
    }
  };

  return (
    <div className="login-shell">
      <div className="login-card">
        <div className="brand-header">
          <div className="brand-badge">PureBili</div>
          <h1>纯净 B 站播放器</h1>
          <p>只看关注 UP 主与我的收藏 · 无推荐 · 无广告</p>
        </div>

        <div className="tab-switch">
          <button
            type="button"
            className={mode === "qr" ? "active" : ""}
            onClick={() => setMode("qr")}
          >
            <QrCode size={18} />
            <span>扫码登录</span>
          </button>
          <button
            type="button"
            className={mode === "password" ? "active" : ""}
            onClick={() => setMode("password")}
          >
            <KeyRound size={18} />
            <span>密码登录</span>
          </button>
        </div>

        {mode === "qr" ? (
          <div className="qr-panel">
            <div className="qr-frame">
              {qrDataUrl ? (
                <img src={qrDataUrl} alt="B站扫码登录" className="qr-img" />
              ) : (
                <div className="qr-placeholder">加载中...</div>
              )}
              {qrExpired && (
                <button
                  type="button"
                  className="qr-refresh-overlay"
                  onClick={loadQrCode}
                >
                  <RefreshCw size={24} />
                  <span>点击刷新二维码</span>
                </button>
              )}
            </div>
            <p className="status-text">{qrStatus}</p>
            <button
              type="button"
              className="btn-secondary"
              onClick={loadQrCode}
            >
              <RefreshCw size={15} />
              <span>重新生成二维码</span>
            </button>
          </div>
        ) : (
          <form className="pwd-panel" onSubmit={handlePasswordSubmit}>
            <div className="field">
              <label>手机号 / 邮箱</label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="输入 B 站账号"
                autoComplete="username"
              />
            </div>
            <div className="field">
              <label>登录密码</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="输入密码（RSA 加密直传 B 站）"
                autoComplete="current-password"
              />
            </div>

            <div className="geetest-hint">
              <ShieldCheck size={16} />
              <span>点击登录将弹出 B 站官方极验验证窗口，完成后自动登录</span>
            </div>

            {pwdStatus && <p className="status-text warn">{pwdStatus}</p>}
            {verifyUrl && (
              <p className="status-text">
                账号触发了安全风控，建议直接切换到「扫码登录」一步完成。
              </p>
            )}

            <button
              type="submit"
              className="btn-primary"
              disabled={pwdLoading}
            >
              {pwdLoading ? "处理中..." : "安全验证并登录"}
            </button>
          </form>
        )}
      </div>
    </div>
  );
};
