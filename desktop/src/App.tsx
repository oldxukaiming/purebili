import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Bookmark,
  BookmarkMinus,
  LogOut,
  Play,
  RefreshCw,
  Search,
  Tv,
  UserMinus,
  Users,
  X,
} from "lucide-react";
import { api } from "./api";
import { LoginView } from "./components/LoginView";
import { VideoPlayerModal } from "./components/VideoPlayerModal";
import type {
  FavFolder,
  FollowingTag,
  FollowingUp,
  UserProfile,
  VideoItem,
} from "./types";

type ActiveTab = "following" | "favorites";

export function App() {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [checkingAuth, setCheckingAuth] = useState(true);
  const [tab, setTab] = useState<ActiveTab>("following");
  const [tvBigMode, setTvBigMode] = useState(false);

  // Following state
  const [followings, setFollowings] = useState<FollowingUp[]>([]);
  const [followingTotal, setFollowingTotal] = useState(0);
  const [followingUpPage, setFollowingUpPage] = useState(1);
  const [followingUpHasMore, setFollowingUpHasMore] = useState(false);
  const [loadingMoreUps, setLoadingMoreUps] = useState(false);
  const [selectedUpMid, setSelectedUpMid] = useState<number | null>(null);
  const [followingVideos, setFollowingVideos] = useState<VideoItem[]>([]);
  const [followingOffset, setFollowingOffset] = useState("");
  const [followingHasMore, setFollowingHasMore] = useState(false);
  const [upPage, setUpPage] = useState(1);

  // Following directory / search modal state
  const [showUpModal, setShowUpModal] = useState(false);
  const [followingTags, setFollowingTags] = useState<FollowingTag[]>([]);
  const [activeTagId, setActiveTagId] = useState<number | null>(null);
  const [upSearchQuery, setUpSearchQuery] = useState("");
  const [modalUps, setModalUps] = useState<FollowingUp[]>([]);
  const [modalUpPage, setModalUpPage] = useState(1);
  const [modalUpTotal, setModalUpTotal] = useState(0);
  const [modalUpHasMore, setModalUpHasMore] = useState(false);
  const [loadingModalUps, setLoadingModalUps] = useState(false);

  // Favorites state
  const [folders, setFolders] = useState<FavFolder[]>([]);
  const [selectedFolderId, setSelectedFolderId] = useState<number | null>(null);
  const [favVideos, setFavVideos] = useState<VideoItem[]>([]);
  const [favPage, setFavPage] = useState(1);
  const [favHasMore, setFavHasMore] = useState(false);

  // Shared UI state
  const [keyword, setKeyword] = useState("");
  const [loadingList, setLoadingList] = useState(false);
  const [listError, setListError] = useState("");
  const [playingVideo, setPlayingVideo] = useState<VideoItem | null>(null);
  const [confirmUnfollowMid, setConfirmUnfollowMid] = useState<number | null>(null);
  const [unfollowing, setUnfollowing] = useState(false);
  const [confirmUnfavAid, setConfirmUnfavAid] = useState<number | null>(null);

  const loadSession = useCallback(async () => {
    setCheckingAuth(true);
    try {
      const p = await api.getProfile();
      setProfile(p);
    } catch {
      setProfile({ is_logged_in: false, uid: 0, uname: "", face: "" });
    } finally {
      setCheckingAuth(false);
    }
  }, []);

  useEffect(() => {
    void loadSession();
  }, [loadSession]);

  const loadFollowingInitial = useCallback(async () => {
    setLoadingList(true);
    setListError("");
    try {
      const [upPageRes, feed] = await Promise.all([
        api.getFollowings(1, 50),
        api.getFollowingFeed(""),
      ]);
      setFollowings(upPageRes.list);
      setFollowingTotal(upPageRes.total);
      setFollowingUpPage(1);
      setFollowingUpHasMore(upPageRes.has_more);
      setFollowingVideos(feed.items);
      setFollowingOffset(feed.next_offset);
      setFollowingHasMore(feed.has_more);
      setSelectedUpMid(null);
    } catch (err) {
      setListError(String(err));
    } finally {
      setLoadingList(false);
    }
  }, []);

  const handleLoadMoreUps = async () => {
    if (loadingMoreUps || !followingUpHasMore) return;
    setLoadingMoreUps(true);
    try {
      const nextPn = followingUpPage + 1;
      const res = await api.getFollowings(nextPn, 50);
      setFollowings((prev) => {
        const seen = new Set(prev.map((u) => u.mid));
        return [...prev, ...res.list.filter((u) => !seen.has(u.mid))];
      });
      setFollowingTotal(res.total);
      setFollowingUpPage(nextPn);
      setFollowingUpHasMore(res.has_more);
    } catch (err) {
      setListError(String(err));
    } finally {
      setLoadingMoreUps(false);
    }
  };

  const loadModalUps = useCallback(
    async (
      pn: number,
      append: boolean,
      tagid: number | null,
      kw: string
    ) => {
      setLoadingModalUps(true);
      try {
        const res = await api.getFollowings(pn, 50, {
          keyword: kw,
          tagid: kw.trim() ? null : tagid,
        });
        setModalUps((prev) => {
          if (!append) return res.list;
          const seen = new Set(prev.map((u) => u.mid));
          return [...prev, ...res.list.filter((u) => !seen.has(u.mid))];
        });
        setModalUpPage(pn);
        const tagCount =
          tagid !== null && !kw.trim()
            ? followingTags.find((t) => t.tagid === tagid)?.count ?? res.total
            : res.total;
        setModalUpTotal(tagCount);
        setModalUpHasMore(res.has_more);
      } catch (err) {
        setListError(String(err));
      } finally {
        setLoadingModalUps(false);
      }
    },
    [followingTags]
  );

  const openUpDirectoryModal = async () => {
    setShowUpModal(true);
    setActiveTagId(null);
    setUpSearchQuery("");
    try {
      const tags = await api.getFollowingTags();
      setFollowingTags(tags);
    } catch {
      // Ignore tag load errors
    }
    void loadModalUps(1, false, null, "");
  };

  const loadFavFoldersAndFirst = useCallback(async () => {
    setLoadingList(true);
    setListError("");
    try {
      const list = await api.getFavFolders();
      setFolders(list);
      if (list.length > 0) {
        const firstId = list[0].id;
        setSelectedFolderId(firstId);
        const res = await api.getFavVideos(firstId, 1, 20, "");
        setFavVideos(res.items);
        setFavPage(1);
        setFavHasMore(res.has_more);
      } else {
        setFavVideos([]);
        setFavHasMore(false);
      }
    } catch (err) {
      setListError(String(err));
    } finally {
      setLoadingList(false);
    }
  }, []);

  useEffect(() => {
    if (!profile?.is_logged_in) return;
    if (tab === "following" && followingVideos.length === 0 && !loadingList) {
      void loadFollowingInitial();
    } else if (tab === "favorites" && folders.length === 0 && !loadingList) {
      void loadFavFoldersAndFirst();
    }
  }, [profile?.is_logged_in, tab]);

  const handleSelectUp = async (mid: number | null) => {
    setConfirmUnfollowMid(null);
    setSelectedUpMid(mid);
    setLoadingList(true);
    setListError("");
    try {
      if (mid === null) {
        const feed = await api.getFollowingFeed("");
        setFollowingVideos(feed.items);
        setFollowingOffset(feed.next_offset);
        setFollowingHasMore(feed.has_more);
      } else {
        const res = await api.getUpVideos(mid, 1, 24);
        setFollowingVideos(res.items);
        setUpPage(1);
        setFollowingHasMore(res.has_more);
      }
    } catch (err) {
      setListError(String(err));
    } finally {
      setLoadingList(false);
    }
  };

  const handleLoadMoreFollowing = async () => {
    if (loadingList || !followingHasMore) return;
    setLoadingList(true);
    try {
      if (selectedUpMid === null) {
        const feed = await api.getFollowingFeed(followingOffset);
        setFollowingVideos((prev) => [...prev, ...feed.items]);
        setFollowingOffset(feed.next_offset);
        setFollowingHasMore(feed.has_more);
      } else {
        const nextPn = upPage + 1;
        const res = await api.getUpVideos(selectedUpMid, nextPn, 24);
        setFollowingVideos((prev) => [...prev, ...res.items]);
        setUpPage(nextPn);
        setFollowingHasMore(res.has_more);
      }
    } catch (err) {
      setListError(String(err));
    } finally {
      setLoadingList(false);
    }
  };

  const handleSelectFolder = async (folderId: number) => {
    setConfirmUnfavAid(null);
    setSelectedFolderId(folderId);
    setLoadingList(true);
    setListError("");
    try {
      const res = await api.getFavVideos(folderId, 1, 20, "");
      setFavVideos(res.items);
      setFavPage(1);
      setFavHasMore(res.has_more);
    } catch (err) {
      setListError(String(err));
    } finally {
      setLoadingList(false);
    }
  };

  const handleLoadMoreFav = async () => {
    if (!selectedFolderId || loadingList || !favHasMore) return;
    setLoadingList(true);
    try {
      const nextPn = favPage + 1;
      const res = await api.getFavVideos(selectedFolderId, nextPn, 20, "");
      setFavVideos((prev) => [...prev, ...res.items]);
      setFavPage(nextPn);
      setFavHasMore(res.has_more);
    } catch (err) {
      setListError(String(err));
    } finally {
      setLoadingList(false);
    }
  };

  const handleUnfollowUp = async (mid: number) => {
    if (confirmUnfollowMid !== mid) {
      setConfirmUnfollowMid(mid);
      return;
    }
    setUnfollowing(true);
    setListError("");
    try {
      await api.unfollowUp(mid);
      setFollowings((prev) => prev.filter((u) => u.mid !== mid));
      setModalUps((prev) => prev.filter((u) => u.mid !== mid));
      setFollowingTotal((t) => Math.max(0, t - 1));
      setConfirmUnfollowMid(null);
      if (selectedUpMid === mid) {
        await handleSelectUp(null);
      }
    } catch (err) {
      setListError(`取消关注失败：${String(err)}`);
    } finally {
      setUnfollowing(false);
    }
  };

  const handleUnfavoriteFromCard = async (video: VideoItem) => {
    if (!selectedFolderId || !video.aid) return;
    if (confirmUnfavAid !== video.aid) {
      setConfirmUnfavAid(video.aid);
      return;
    }
    setListError("");
    try {
      await api.unfavoriteVideo(video.aid, [selectedFolderId]);
      setConfirmUnfavAid(null);
      setFavVideos((prev) => prev.filter((item) => item.aid !== video.aid));
      setFolders((prev) =>
        prev.map((f) =>
          f.id === selectedFolderId && f.media_count > 0
            ? { ...f, media_count: f.media_count - 1 }
            : f
        )
      );
    } catch (err) {
      setListError(`取消收藏失败：${String(err)}`);
    }
  };

  const handleLogout = async () => {
    await api.logout();
    setFollowings([]);
    setFollowingVideos([]);
    setFolders([]);
    setFavVideos([]);
    setPlayingVideo(null);
    await loadSession();
  };

  const selectedFolder = folders.find((f) => f.id === selectedFolderId) ?? null;
  const canUnfavInCurrentFolder =
    tab === "favorites" && Boolean(selectedFolder && !selectedFolder.is_collected);

  const rawVideos = tab === "following" ? followingVideos : favVideos;
  const visibleVideos = useMemo(() => {
    const q = keyword.trim().toLowerCase();
    if (!q) return rawVideos;
    return rawVideos.filter(
      (v) =>
        v.title.toLowerCase().includes(q) ||
        v.author_name.toLowerCase().includes(q)
    );
  }, [rawVideos, keyword]);

  if (checkingAuth) {
    return <div className="splash-screen">正在连接 B 站检查登录状态...</div>;
  }

  if (!profile?.is_logged_in) {
    return <LoginView onSuccess={loadSession} />;
  }

  return (
    <div className={`app-shell ${tvBigMode ? "tv-mode" : ""}`}>
      <aside className="sidebar">
        <div className="sidebar-brand">
          <div className="brand-dot" />
          <div>
            <div className="brand-name">PureBili</div>
            <div className="brand-sub">无推荐 · 无广告</div>
          </div>
        </div>

        <nav className="nav-menu">
          <button
            type="button"
            className={`nav-item ${tab === "following" ? "active" : ""}`}
            onClick={() => setTab("following")}
          >
            <Users size={20} />
            <span>
              关注 UP 主{followingTotal > 0 ? ` (${followingTotal})` : ""}
            </span>
          </button>
          <button
            type="button"
            className={`nav-item ${tab === "favorites" ? "active" : ""}`}
            onClick={() => setTab("favorites")}
          >
            <Bookmark size={20} />
            <span>我的收藏夹</span>
          </button>
        </nav>

        <div className="sidebar-footer">
          <button
            type="button"
            className={`nav-item compact ${tvBigMode ? "active" : ""}`}
            onClick={() => setTvBigMode((v) => !v)}
            title="放大卡片与字体，适配大屏显示"
          >
            <Tv size={18} />
            <span>{tvBigMode ? "标准桌面模式" : "大屏精简模式"}</span>
          </button>

          <div className="user-box">
            {profile.face ? (
              <img src={profile.face} alt={profile.uname} className="user-avatar" />
            ) : (
              <div className="user-avatar placeholder" />
            )}
            <div className="user-meta">
              <div className="user-name">{profile.uname}</div>
              <div className="user-uid">UID: {profile.uid}</div>
            </div>
            <button
              type="button"
              className="btn-icon"
              onClick={handleLogout}
              title="退出登录"
            >
              <LogOut size={16} />
            </button>
          </div>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="topbar-title">
            {tab === "following"
              ? `关注 UP 主视频${followingTotal > 0 ? `（共关注 ${followingTotal} 位）` : ""}`
              : "我的收藏夹视频"}
          </div>

          <div className="topbar-actions">
            <div className="search-box">
              <Search size={16} />
              <input
                type="text"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="仅在当前列表中搜索标题或 UP 主..."
              />
            </div>

            <button
              type="button"
              className="btn-secondary"
              onClick={() =>
                tab === "following"
                  ? handleSelectUp(selectedUpMid)
                  : selectedFolderId && handleSelectFolder(selectedFolderId)
              }
            >
              <RefreshCw size={15} />
              <span>刷新</span>
            </button>
          </div>
        </header>

        {tab === "following" ? (
          <div className="chip-strip-row">
            <div className="chip-strip">
              <button
                type="button"
                className={`up-chip ${selectedUpMid === null ? "active" : ""}`}
                onClick={() => handleSelectUp(null)}
              >
                <span>全部关注动态</span>
              </button>
              {followings.map((up) => (
                <button
                  key={up.mid}
                  type="button"
                  className={`up-chip ${selectedUpMid === up.mid ? "active" : ""}`}
                  onClick={() => handleSelectUp(up.mid)}
                >
                  {up.face && <img src={up.face} alt={up.uname} />}
                  <span>{up.uname}</span>
                </button>
              ))}
              {followingUpHasMore && (
                <button
                  type="button"
                  className="up-chip"
                  disabled={loadingMoreUps}
                  onClick={() => void handleLoadMoreUps()}
                >
                  <span>
                    {loadingMoreUps
                      ? "加载中..."
                      : `加载更多 (${followings.length}/${followingTotal})`}
                  </span>
                </button>
              )}
            </div>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => void openUpDirectoryModal()}
            >
              <Users size={15} />
              <span>全部关注 ({followingTotal || followings.length})</span>
            </button>
            {selectedUpMid !== null && (
              <button
                type="button"
                className={`btn-unfollow ${confirmUnfollowMid === selectedUpMid ? "confirming" : ""}`}
                disabled={unfollowing}
                onClick={() => void handleUnfollowUp(selectedUpMid)}
              >
                <UserMinus size={15} />
                <span>
                  {unfollowing
                    ? "处理中..."
                    : confirmUnfollowMid === selectedUpMid
                    ? "再次点击确认取关"
                    : "取消关注"}
                </span>
              </button>
            )}
          </div>
        ) : (
          <div className="chip-strip">
            {folders.map((folder) => (
              <button
                key={folder.id}
                type="button"
                className={`up-chip ${selectedFolderId === folder.id ? "active" : ""}`}
                onClick={() => handleSelectFolder(folder.id)}
              >
                <span>
                  {folder.title} ({folder.media_count})
                </span>
              </button>
            ))}
          </div>
        )}

        {listError && <div className="error-banner">{listError}</div>}

        <div className="video-grid">
          {visibleVideos.map((v) => (
            <div
              key={`${v.bvid}_${v.aid}`}
              className={`video-card ${v.invalid ? "invalid" : ""}`}
            >
              <button
                type="button"
                className="video-card-trigger"
                disabled={v.invalid}
                onClick={() => !v.invalid && setPlayingVideo(v)}
              >
                <div className="thumb-wrap">
                  {v.cover && <img src={v.cover} alt={v.title} loading="lazy" />}
                  <span className="duration-tag">{v.duration_text || "视频"}</span>
                  {!v.invalid && (
                    <div className="play-hover">
                      <Play size={28} fill="currentColor" />
                    </div>
                  )}
                </div>
                <div className="card-info">
                  <div className="card-title">{v.title}</div>
                </div>
              </button>
              <div className="card-footer">
                <span className="card-author">{v.author_name}</span>
                {canUnfavInCurrentFolder && v.aid > 0 ? (
                  <button
                    type="button"
                    className={`btn-card-unfav ${
                      confirmUnfavAid === v.aid ? "confirming" : ""
                    }`}
                    onClick={() => void handleUnfavoriteFromCard(v)}
                  >
                    <BookmarkMinus size={13} />
                    <span>{confirmUnfavAid === v.aid ? "确认取藏?" : "取消收藏"}</span>
                  </button>
                ) : (
                  <span className="card-pub">{v.pub_text}</span>
                )}
              </div>
            </div>
          ))}
        </div>

        {!loadingList && visibleVideos.length === 0 && !listError && (
          <div className="empty-state">当前条件下暂无视频</div>
        )}

        <div className="load-more-row">
          {(tab === "following" ? followingHasMore : favHasMore) && (
            <button
              type="button"
              className="btn-secondary"
              disabled={loadingList}
              onClick={
                tab === "following"
                  ? handleLoadMoreFollowing
                  : handleLoadMoreFav
              }
            >
              {loadingList ? "加载中..." : "加载更多视频"}
            </button>
          )}
        </div>
      </main>

      {playingVideo && (
        <VideoPlayerModal
          video={playingVideo}
          playlist={visibleVideos.filter((v) => !v.invalid)}
          isFollowingAuthor={followings.some((u) => u.mid === playingVideo.author_mid)}
          activeFavFolderId={canUnfavInCurrentFolder ? selectedFolderId : null}
          onUnfollowed={(mid) => {
            setFollowings((prev) => prev.filter((u) => u.mid !== mid));
            setModalUps((prev) => prev.filter((u) => u.mid !== mid));
            setFollowingTotal((t) => Math.max(0, t - 1));
            if (selectedUpMid === mid) {
              void handleSelectUp(null);
            }
          }}
          onUnfavorited={(aid, removedIds) => {
            if (selectedFolderId && removedIds.includes(selectedFolderId)) {
              setFavVideos((prev) => prev.filter((item) => item.aid !== aid));
              setFolders((prev) =>
                prev.map((f) =>
                  f.id === selectedFolderId && f.media_count > 0
                    ? { ...f, media_count: f.media_count - 1 }
                    : f
                )
              );
            }
          }}
          onClose={() => setPlayingVideo(null)}
          onSelectVideo={(next) => setPlayingVideo(next)}
        />
      )}

      {showUpModal && (
        <div
          className="cast-dialog-backdrop"
          onClick={() => setShowUpModal(false)}
        >
          <div
            className="cast-dialog up-directory-dialog"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="cast-dialog-header">
              <div className="cast-title">
                <Users size={18} />
                <span>全部关注 UP 主（共 {followingTotal} 位）</span>
              </div>
              <button
                type="button"
                className="btn-icon"
                onClick={() => setShowUpModal(false)}
              >
                <X size={18} />
              </button>
            </div>

            <form
              className="up-modal-search"
              onSubmit={(e) => {
                e.preventDefault();
                void loadModalUps(1, false, null, upSearchQuery);
              }}
            >
              <div className="search-box" style={{ flex: 1 }}>
                <Search size={15} />
                <input
                  type="text"
                  value={upSearchQuery}
                  onChange={(e) => setUpSearchQuery(e.target.value)}
                  placeholder="在全部关注中搜索 UP 主昵称..."
                />
              </div>
              <button type="submit" className="btn-primary">
                搜索
              </button>
              {upSearchQuery && (
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => {
                    setUpSearchQuery("");
                    void loadModalUps(1, false, activeTagId, "");
                  }}
                >
                  清空
                </button>
              )}
            </form>

            {followingTags.length > 0 && !upSearchQuery.trim() && (
              <div className="chip-strip">
                <button
                  type="button"
                  className={`up-chip ${activeTagId === null ? "active" : ""}`}
                  onClick={() => {
                    setActiveTagId(null);
                    void loadModalUps(1, false, null, "");
                  }}
                >
                  <span>全部 ({followingTotal})</span>
                </button>
                {followingTags.map((tag) => (
                  <button
                    key={tag.tagid}
                    type="button"
                    className={`up-chip ${activeTagId === tag.tagid ? "active" : ""}`}
                    onClick={() => {
                      setActiveTagId(tag.tagid);
                      void loadModalUps(1, false, tag.tagid, "");
                    }}
                  >
                    <span>
                      {tag.name} ({tag.count})
                    </span>
                  </button>
                ))}
              </div>
            )}

            <div className="up-modal-meta">
              已显示 {modalUps.length} / {modalUpTotal} 位 UP 主
            </div>

            <div className="up-modal-list">
              {modalUps.map((up) => (
                <div key={up.mid} className="up-modal-item">
                  <button
                    type="button"
                    className="up-modal-pick"
                    onClick={() => {
                      setFollowings((prev) =>
                        prev.some((x) => x.mid === up.mid) ? prev : [up, ...prev]
                      );
                      setShowUpModal(false);
                      void handleSelectUp(up.mid);
                    }}
                  >
                    {up.face && <img src={up.face} alt={up.uname} />}
                    <div>
                      <div className="up-modal-name">{up.uname}</div>
                      {up.sign && <div className="up-modal-sign">{up.sign}</div>}
                    </div>
                  </button>
                  <button
                    type="button"
                    className={`btn-card-unfav ${
                      confirmUnfollowMid === up.mid ? "confirming" : ""
                    }`}
                    onClick={() => void handleUnfollowUp(up.mid)}
                  >
                    {confirmUnfollowMid === up.mid ? "确认取关?" : "取关"}
                  </button>
                </div>
              ))}
            </div>

            {modalUpHasMore && (
              <div className="load-more-row" style={{ padding: "8px 0 0" }}>
                <button
                  type="button"
                  className="btn-secondary"
                  disabled={loadingModalUps}
                  onClick={() =>
                    void loadModalUps(
                      modalUpPage + 1,
                      true,
                      activeTagId,
                      upSearchQuery
                    )
                  }
                >
                  {loadingModalUps ? "加载中..." : "加载更多 UP 主 (+50)"}
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
