import React, { useEffect, useRef, useState } from "react";
import * as dashjs from "dashjs";
import {
  BookmarkMinus,
  Cast,
  ChevronLeft,
  ChevronRight,
  Layers,
  Maximize2,
  Pause,
  Play,
  RefreshCw,
  SkipForward,
  Sliders,
  Square,
  UserMinus,
  X,
} from "lucide-react";
import { api } from "../api";
import type { DlnaDevice, PlayInfo, VideoDetail, VideoItem } from "../types";

interface VideoPlayerModalProps {
  video: VideoItem;
  playlist: VideoItem[];
  isFollowingAuthor: boolean;
  activeFavFolderId: number | null;
  onUnfollowed: (mid: number) => void;
  onUnfavorited: (aid: number, folderIds: number[]) => void;
  onClose: () => void;
  onSelectVideo: (video: VideoItem) => void;
}

const SPEED_OPTIONS = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

export const VideoPlayerModal: React.FC<VideoPlayerModalProps> = ({
  video,
  playlist,
  isFollowingAuthor,
  activeFavFolderId,
  onUnfollowed,
  onUnfavorited,
  onClose,
  onSelectVideo,
}) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const dashRef = useRef<dashjs.MediaPlayerClass | null>(null);

  const [detail, setDetail] = useState<VideoDetail | null>(null);
  const [currentPartIdx, setCurrentPartIdx] = useState(0);
  const [requestedQn, setRequestedQn] = useState(80);
  const [playInfo, setPlayInfo] = useState<PlayInfo | null>(null);
  const [streamMode, setStreamMode] = useState<"dash" | "mp4">("dash");
  const [playbackRate, setPlaybackRate] = useState(1.0);
  const [errorMsg, setErrorMsg] = useState("");
  const [loading, setLoading] = useState(true);

  // Unfollow state inside player
  const [unfollowConfirm, setUnfollowConfirm] = useState(false);
  const [unfollowedDone, setUnfollowedDone] = useState(false);

  // Unfavorite state inside player
  const [unfavConfirm, setUnfavConfirm] = useState(false);
  const [unfavDone, setUnfavDone] = useState(false);
  const [pendingUnfavIds, setPendingUnfavIds] = useState<number[]>([]);
  const [unfavLabel, setUnfavLabel] = useState("取消收藏");

  // DLNA Cast state
  const [showCastPanel, setShowCastPanel] = useState(false);
  const [dlnaDevices, setDlnaDevices] = useState<DlnaDevice[]>([]);
  const [scanningDlna, setScanningDlna] = useState(false);
  const [castingDevice, setCastingDevice] = useState<DlnaDevice | null>(null);
  const [castStatus, setCastStatus] = useState("");

  const currentIdxInPlaylist = playlist.findIndex(
    (v) => v.bvid === video.bvid
  );
  const nextVideo =
    currentIdxInPlaylist >= 0 && currentIdxInPlaylist + 1 < playlist.length
      ? playlist[currentIdxInPlaylist + 1]
      : null;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setErrorMsg("");
    setCurrentPartIdx(0);
    setDetail(null);
    setPlayInfo(null);
    setUnfollowConfirm(false);
    setUnfollowedDone(false);
    setUnfavConfirm(false);
    setUnfavDone(false);
    setPendingUnfavIds([]);
    setUnfavLabel("取消收藏");

    api
      .getVideoDetail(video.bvid)
      .then((d) => {
        if (!cancelled) {
          setDetail(d);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setErrorMsg(`加载视频信息失败：${String(err)}`);
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [video.bvid]);

  useEffect(() => {
    if (!detail || detail.pages.length === 0) return;
    const part = detail.pages[currentPartIdx] ?? detail.pages[0];
    let cancelled = false;
    setLoading(true);
    setErrorMsg("");

    api
      .getPlayInfo(detail.bvid, part.cid, requestedQn)
      .then((info) => {
        if (!cancelled) {
          setPlayInfo(info);
          if (!info.dash_mpd_url && info.mp4_url) {
            setStreamMode("mp4");
          }
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setErrorMsg(`获取播放流失败：${String(err)}`);
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [detail, currentPartIdx, requestedQn]);

  useEffect(() => {
    const el = videoRef.current;
    if (!el || !playInfo) return;

    if (dashRef.current) {
      dashRef.current.destroy();
      dashRef.current = null;
    }

    if (streamMode === "dash" && playInfo.dash_mpd_url) {
      const player = dashjs.MediaPlayer().create();
      player.initialize(el, playInfo.dash_mpd_url, true);
      dashRef.current = player;
    } else if (playInfo.mp4_url) {
      el.src = playInfo.mp4_url;
      el.play().catch(() => {});
    }

    el.playbackRate = playbackRate;

    return () => {
      if (dashRef.current) {
        dashRef.current.destroy();
        dashRef.current = null;
      }
    };
  }, [playInfo, streamMode]);

  useEffect(() => {
    if (videoRef.current) {
      videoRef.current.playbackRate = playbackRate;
    }
  }, [playbackRate]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const el = videoRef.current;
      if (e.key === "Escape") {
        if (showCastPanel) {
          setShowCastPanel(false);
        } else {
          onClose();
        }
        return;
      }
      if (!el) return;
      if (e.key === " " || e.key === "Enter") {
        if (
          document.activeElement?.tagName !== "BUTTON" &&
          document.activeElement?.tagName !== "SELECT"
        ) {
          e.preventDefault();
          if (el.paused) el.play().catch(() => {});
          else el.pause();
        }
      } else if (e.key === "ArrowRight") {
        e.preventDefault();
        el.currentTime = Math.min(el.duration || 0, el.currentTime + 10);
      } else if (e.key === "ArrowLeft") {
        e.preventDefault();
        el.currentTime = Math.max(0, el.currentTime - 10);
      } else if (e.key === "f" || e.key === "F") {
        e.preventDefault();
        if (document.fullscreenElement) {
          document.exitFullscreen().catch(() => {});
        } else {
          el.requestFullscreen().catch(() => {});
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose, showCastPanel]);

  const handleVideoEnded = () => {
    if (detail && currentPartIdx + 1 < detail.pages.length) {
      setCurrentPartIdx((idx) => idx + 1);
    } else if (nextVideo) {
      onSelectVideo(nextVideo);
    }
  };

  const handleUnfollowAuthor = async () => {
    const targetMid = detail?.author_mid || video.author_mid;
    if (!targetMid) return;
    if (!unfollowConfirm) {
      setUnfollowConfirm(true);
      return;
    }
    try {
      await api.unfollowUp(targetMid);
      setUnfollowedDone(true);
      setUnfollowConfirm(false);
      onUnfollowed(targetMid);
    } catch (err) {
      setErrorMsg(`取消关注失败：${String(err)}`);
    }
  };

  const handleUnfavoriteVideo = async () => {
    const aid = detail?.aid || video.aid;
    if (!aid) {
      setErrorMsg("未获取到视频 aid，无法取消收藏");
      return;
    }
    if (!unfavConfirm) {
      if (activeFavFolderId) {
        setPendingUnfavIds([activeFavFolderId]);
        setUnfavConfirm(true);
        return;
      }
      try {
        const folders = await api.getVideoFavFolders(aid);
        const favedIds = folders.filter((f) => f.fav_state === 1).map((f) => f.id);
        if (favedIds.length === 0) {
          setUnfavLabel("未在自建收藏夹");
          setUnfavDone(true);
          return;
        }
        setPendingUnfavIds(favedIds);
        setUnfavConfirm(true);
      } catch (err) {
        setErrorMsg(`查询收藏状态失败：${String(err)}`);
      }
      return;
    }

    try {
      await api.unfavoriteVideo(aid, pendingUnfavIds);
      setUnfavDone(true);
      setUnfavConfirm(false);
      setUnfavLabel("已取消收藏");
      onUnfavorited(aid, pendingUnfavIds);
    } catch (err) {
      setErrorMsg(`取消收藏失败：${String(err)}`);
    }
  };

  const scanDlna = async () => {
    setScanningDlna(true);
    setCastStatus("正在搜索同一局域网下的支持 DLNA 电视设备...");
    try {
      const list = await api.discoverDlnaDevices();
      setDlnaDevices(list);
      setCastStatus(
        list.length > 0
          ? `已发现 ${list.length} 台支持投屏的电视/盒子设备`
          : "未搜索到 DLNA 电视设备，请确认电视与本机在同一 Wi-Fi 并开启投屏服务"
      );
    } catch (err) {
      setCastStatus(`搜索设备失败：${String(err)}`);
    } finally {
      setScanningDlna(false);
    }
  };

  const handleOpenCast = () => {
    setShowCastPanel(true);
    void scanDlna();
  };

  const handleCastTo = async (dev: DlnaDevice) => {
    if (!detail || detail.pages.length === 0) return;
    const part = detail.pages[currentPartIdx] ?? detail.pages[0];
    setCastStatus(`正在向「${dev.name}」推送视频流...`);
    try {
      await api.castVideoToDlna({
        controlUrl: dev.control_url,
        bvid: detail.bvid,
        cid: part.cid,
        qn: requestedQn,
        title: detail.title,
      });
      setCastingDevice(dev);
      videoRef.current?.pause();
      setCastStatus(`已投屏到「${dev.name}」并开始播放（本机已自动暂停）`);
    } catch (err) {
      setCastStatus(`投屏失败：${String(err)}`);
    }
  };

  const handleControlCast = async (cmd: "play" | "pause" | "stop") => {
    if (!castingDevice) return;
    try {
      await api.controlDlna(castingDevice.control_url, cmd);
      if (cmd === "stop") {
        setCastingDevice(null);
        setCastStatus("已停止电视投屏");
      } else if (cmd === "pause") {
        setCastStatus(`已暂停「${castingDevice.name}」播放`);
      } else {
        setCastStatus(`正在「${castingDevice.name}」继续播放`);
      }
    } catch (err) {
      setCastStatus(`控制失败：${String(err)}`);
    }
  };

  return (
    <div className="player-overlay">
      <div className="player-topbar">
        <button type="button" className="btn-back" onClick={onClose}>
          <ChevronLeft size={20} />
          <span>返回列表 (Esc)</span>
        </button>
        <div className="player-title-wrap">
          <h2>{detail?.title || video.title}</h2>
          <span className="player-author">
            UP: {detail?.author_name || video.author_name}
          </span>
        </div>

        {(isFollowingAuthor || unfollowedDone) && (
          <button
            type="button"
            className={`btn-unfollow ${unfollowConfirm ? "confirming" : ""}`}
            disabled={unfollowedDone}
            onClick={() => void handleUnfollowAuthor()}
          >
            <UserMinus size={15} />
            <span>
              {unfollowedDone
                ? "已取关"
                : unfollowConfirm
                ? "确认取关？"
                : "取消关注"}
            </span>
          </button>
        )}

        <button
          type="button"
          className={`btn-unfollow ${unfavConfirm ? "confirming" : ""}`}
          disabled={unfavDone}
          onClick={() => void handleUnfavoriteVideo()}
        >
          <BookmarkMinus size={15} />
          <span>{unfavConfirm ? "确认取藏？" : unfavLabel}</span>
        </button>

        <button
          type="button"
          className="btn-secondary"
          onClick={handleOpenCast}
        >
          <Cast size={16} />
          <span>{castingDevice ? `投屏中: ${castingDevice.name}` : "投屏到电视"}</span>
        </button>

        {nextVideo && (
          <button
            type="button"
            className="btn-secondary"
            onClick={() => onSelectVideo(nextVideo)}
          >
            <SkipForward size={16} />
            <span>下一视频</span>
          </button>
        )}
      </div>

      <div className="player-body">
        <div className="video-stage">
          {loading && <div className="player-loading">正在解析视频流...</div>}
          {errorMsg && <div className="player-error">{errorMsg}</div>}
          <video
            ref={videoRef}
            controls
            autoPlay
            playsInline
            onEnded={handleVideoEnded}
            className="video-element"
          />

          <div className="player-controls-bar">
            <div className="ctrl-group">
              <Sliders size={15} />
              <span>画质</span>
              <select
                value={requestedQn}
                onChange={(e) => setRequestedQn(Number(e.target.value))}
              >
                {(playInfo?.qualities.length
                  ? playInfo.qualities
                  : [
                      { qn: 80, desc: "1080P 高清" },
                      { qn: 64, desc: "720P 高清" },
                      { qn: 32, desc: "480P 清晰" },
                    ]
                ).map((q) => (
                  <option key={q.qn} value={q.qn}>
                    {q.desc}
                  </option>
                ))}
              </select>
            </div>

            <div className="ctrl-group">
              <span>倍速</span>
              <select
                value={playbackRate}
                onChange={(e) => setPlaybackRate(Number(e.target.value))}
              >
                {SPEED_OPTIONS.map((r) => (
                  <option key={r} value={r}>
                    {r}x
                  </option>
                ))}
              </select>
            </div>

            <div className="ctrl-group">
              <span>解码模式</span>
              <button
                type="button"
                className="btn-chip"
                onClick={() =>
                  setStreamMode((m) => (m === "dash" ? "mp4" : "dash"))
                }
              >
                {streamMode === "dash" ? "DASH 高清" : "MP4 兼容"}
              </button>
            </div>

            <button
              type="button"
              className="btn-chip"
              onClick={() => {
                const el = videoRef.current;
                if (!el) return;
                if (document.fullscreenElement) {
                  document.exitFullscreen().catch(() => {});
                } else {
                  el.requestFullscreen().catch(() => {});
                }
              }}
            >
              <Maximize2 size={14} />
              <span>全屏 (F)</span>
            </button>
          </div>
        </div>

        <aside className="player-sidebar">
          {detail && detail.pages.length > 1 && (
            <div className="side-section">
              <h3>
                <Layers size={16} />
                <span>分 P 选集 ({detail.pages.length})</span>
              </h3>
              <div className="parts-list">
                {detail.pages.map((p, idx) => (
                  <button
                    key={p.cid}
                    type="button"
                    className={`part-item ${idx === currentPartIdx ? "active" : ""}`}
                    onClick={() => setCurrentPartIdx(idx)}
                  >
                    <span className="part-num">P{p.page}</span>
                    <span className="part-name">{p.part}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {detail?.desc && (
            <div className="side-section">
              <h3>视频简介</h3>
              <p className="video-desc">{detail.desc}</p>
            </div>
          )}

          {playlist.length > 1 && (
            <div className="side-section">
              <h3>当前列表</h3>
              <div className="mini-playlist">
                {playlist.slice(0, 20).map((item) => (
                  <button
                    key={item.bvid}
                    type="button"
                    className={`mini-item ${item.bvid === video.bvid ? "active" : ""}`}
                    onClick={() => onSelectVideo(item)}
                  >
                    <span className="mini-title">{item.title}</span>
                    <span className="mini-meta">
                      {item.author_name} · {item.duration_text}
                    </span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </aside>
      </div>

      {showCastPanel && (
        <div className="cast-dialog-backdrop" onClick={() => setShowCastPanel(false)}>
          <div className="cast-dialog" onClick={(e) => e.stopPropagation()}>
            <div className="cast-dialog-header">
              <div className="cast-title">
                <Cast size={18} />
                <span>局域网电视投屏 (DLNA)</span>
              </div>
              <button
                type="button"
                className="btn-icon"
                onClick={() => setShowCastPanel(false)}
              >
                <X size={18} />
              </button>
            </div>

            <p className="cast-status">{castStatus}</p>

            <div className="cast-device-list">
              {dlnaDevices.map((dev) => (
                <button
                  key={dev.control_url}
                  type="button"
                  className={`cast-device-item ${
                    castingDevice?.control_url === dev.control_url ? "active" : ""
                  }`}
                  onClick={() => void handleCastTo(dev)}
                >
                  <div>
                    <div className="cast-dev-name">{dev.name}</div>
                    <div className="cast-dev-host">{dev.host}</div>
                  </div>
                  <span className="cast-dev-action">
                    {castingDevice?.control_url === dev.control_url ? "正在投屏" : "点击投屏"}
                  </span>
                </button>
              ))}
            </div>

            {castingDevice && (
              <div className="cast-remote-bar">
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => void handleControlCast("play")}
                >
                  <Play size={14} />
                  <span>继续</span>
                </button>
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => void handleControlCast("pause")}
                >
                  <Pause size={14} />
                  <span>暂停</span>
                </button>
                <button
                  type="button"
                  className="btn-unfollow"
                  onClick={() => void handleControlCast("stop")}
                >
                  <Square size={14} />
                  <span>停止投屏</span>
                </button>
              </div>
            )}

            <div className="cast-dialog-footer">
              <span className="cast-tip">
                由本机代理自动注入 B 站防盗链头，保持本软件开启即可在电视端流畅播放
              </span>
              <button
                type="button"
                className="btn-secondary"
                disabled={scanningDlna}
                onClick={() => void scanDlna()}
              >
                <RefreshCw size={14} />
                <span>{scanningDlna ? "搜索中..." : "重新扫描"}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="shortcut-hint">
        快捷键：空格 暂停/播放 · 左右箭头 快进/退 10 秒 · F 全屏 · Esc 返回
        <ChevronRight size={14} />
        播放结束自动连播下一集
      </div>
    </div>
  );
};
