"use client";
import { useConfirmation } from "./confirmation";
import { useEffect, useRef, useState } from "react";
import { ApiError } from "../lib/browser-api";
import { useWorkspaceApi } from "./workspace-context";
import { EditorPublicPage } from "./editor-public-page";
import { DraftRecovery, useEditorRecovery } from "./editor-recovery";
import { DraftLibrary } from "./draft-library";
import { recoveredFile, type LocalDraft } from "../lib/local-drafts";
import {
  imageUpload,
  type ImageInsertion,
  type ImageUpload,
} from "../lib/image-upload";
import type {
  GalleryStatus,
  RepositoryFile,
  RepositoryTree,
  PatchResult,
} from "../lib/types";
import { saveRepositoryFile } from "../lib/repository-write";
import { Markdown } from "./markdown";
import { message, type Identity } from "./admin";
import { AssetPicker } from "./asset-picker";
import { Gallery } from "./gallery";
import { FileTree } from "./file-tree";
import { FilenameSearch } from "./filename-search";
import { SearchHighlight } from "./search-highlight";
import { FolderPicker } from "./folder-picker";
import { ExportDialog } from "./export-dialog";
import { HistoryDialog } from "./history-dialog";
import { DiagnosticMessage } from "./diagnostic";
import { Icon } from "./ui/icons";
import { NoteLibrary } from "./note-library";
import {
  contentRoot,
  folderLocation,
  inContentRoot,
  readDirectory,
} from "../lib/repository-directory";
import {
  navigationFolder,
  parentDirectory,
  privateCreationPath,
  type ContentLocation,
} from "../lib/repository-navigation";

type Preview = {
  body?: string;
  images?: Record<string, string>;
  links?: Record<string, string>;
  downloads?: Record<string, string>;
  playback?: Record<string, string>;
  gallery?: { src: string; original: string; alt: string }[];
  galleryStatus: GalleryStatus;
};
export function Editor({
  identity,
  onDirtyChange,
  onNavigate,
}: {
  identity: Identity;
  onDirtyChange: (dirty: boolean, discard?: () => void) => void;
  onNavigate: (location: ContentLocation, replace?: boolean) => void;
}) {
  const api = useWorkspaceApi();
  const confirm = useConfirmation();
  const [tree, setTree] = useState<RepositoryTree | null>(null);
  const [file, setFile] = useState<RepositoryFile | null>(null);
  const [source, setSource] = useState("");
  const [path, setPath] = useState("");
  const [folder, setFolder] = useState("");
  const [creation, setCreation] = useState<"note" | "folder" | null>(null);
  const creationTrigger = useRef<HTMLButtonElement | null>(null);
  const alive = useRef(false);
  const pageRequest = useRef(0);
  const [pagePending, setPagePending] = useState(false);
  const [search, setSearch] = useState<{
    query: string;
    items: { path: string; title: string; snippet: string }[];
  } | null>(null);
  const [working, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [incomingImage, setIncomingImage] = useState<ImageUpload | null>(null);
  const busy = working || uploading;
  const [exportSelection, setExportSelection] = useState<{
    source: string;
    returnFocus: HTMLElement | null;
  } | null>(null);
  const [historySelection, setHistorySelection] = useState<{
    file: RepositoryFile;
    returnFocus: HTMLElement | null;
  } | null>(null);
  const [moveSelection, setMoveSelection] = useState<{
    source: string;
    commit: string;
    returnFocus: HTMLElement | null;
  } | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [conflict, setConflict] = useState(false);
  const [preview, setPreview] = useState<Preview>({
    galleryStatus: "COMPLETE",
  });
  const [previewError, setPreviewError] = useState("");
  const [previewVersion, setPreviewVersion] = useState(0);
  const [view, setView] = useState("split");
  const textarea = useRef<HTMLTextAreaElement>(null);
  const currentSource = useRef(source);
  currentSource.current = source;
  const editorRoot = useRef<HTMLDivElement>(null);
  const editorMain = useRef<HTMLElement>(null);
  const unreadable =
    file !== null && file.source === null && !file.expectedAbsence;
  const writable = identity.capabilities.includes(
    file?.publicScope ? "PUBLISH" : "WRITE_PRIVATE",
  );
  const dirty =
    file !== null &&
    ((file.expectedAbsence && writable) ||
      source !== (file.source ?? "") ||
      path !== file.path);
  const recovery = useEditorRecovery(
    identity,
    file,
    path,
    source,
    dirty,
    writable,
  );
  async function reloadTree() {
    const next = await api<RepositoryTree>("/api/admin/repository/tree");
    setTree(next);
    return next;
  }
  useEffect(() => {
    alive.current = true;
    void reloadTree()
      .then(() => {
        if (!alive.current) return;
        const query = new URLSearchParams(window.location.search);
        const initial = query.get("path");
        const selectedFolder = navigationFolder(
          query.get("folder") ?? parentDirectory(initial ?? ""),
        );
        setFolder(selectedFolder);
        if (initial)
          void open(initial, { folder: selectedFolder, replace: true });
      })
      .catch((error) => setError(message(error)));
    return () => {
      alive.current = false;
    };
  }, []);
  useEffect(() => {
    onDirtyChange(dirty || uploading, recovery.discard);
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    if (dirty || uploading) window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, uploading, onDirtyChange, recovery.discard]);
  useEffect(() => {
    setIncomingImage(null);
  }, [path]);
  useEffect(() => {
    setPreview({ galleryStatus: "COMPLETE" });
    setPreviewError("");
    if (!file || unreadable || !path) return;
    let active = true;
    const timer = window.setTimeout(() => {
      void api<Preview>("/api/admin/repository/preview", {
        method: "POST",
        body: { path, body: source, commit: file.commit },
      })
        .then((value) => {
          if (active) setPreview(value);
        })
        .catch((error) => {
          if (active) setPreviewError(message(error));
        });
    }, 500);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [path, file?.commit, source, previewVersion, unreadable]);
  function navigate(nextPath: string, nextFolder = folder, replace = false) {
    if (!alive.current) return;
    setFolder(nextFolder);
    onNavigate({ folder: nextFolder, path: nextPath }, replace);
  }
  async function open(
    target: string,
    options: {
      folder?: string;
      replace?: boolean;
      create?: "note" | "folder";
    } = {},
  ) {
    if (
      dirty &&
      !(await confirm({
        title: "放弃未保存的修改？",
        description: `打开「${target}」将丢弃当前编辑框中未保存的修改。`,
        confirmLabel: "放弃并打开",
      }))
    )
      return;
    pageRequest.current++;
    setPagePending(false);
    setBusy(true);
    setError("");
    setNotice("");
    setConflict(false);
    try {
      const result = await api<RepositoryFile>(
        "/api/admin/repository/file?" + new URLSearchParams({ path: target }),
      );
      if (!alive.current) return;
      if (options.create && !result.expectedAbsence)
        throw new ApiError(
          409,
          "同名文件已经存在，请换一个名称，或从文件树打开它。",
        );
      if (options.create === "folder") {
        const directory = await readDirectory(
          api,
          result.commit,
          parentDirectory(result.path),
        );
        if (!alive.current) return;
        if (!directory.expectedAbsence)
          throw new ApiError(
            409,
            "这个分类已经存在，请换一个名称，或在文件树选择它。",
          );
      }
      if (dirty) recovery.discard();
      recovery.opened(result);
      setPreview({ galleryStatus: "COMPLETE" });
      setFile(result);
      setPath(result.path);
      setSource(
        result.source ??
          (options.create ? `---\nid: ${crypto.randomUUID()}\n---\n` : ""),
      );
      setPreviewVersion((version) => version + 1);
      navigate(result.path, options.folder ?? folder, options.replace);
      // Narrow screens stack the file list under the editor; bring the opened note into view.
      if (window.matchMedia?.("(max-width: 1100px)").matches)
        requestAnimationFrame(() =>
          editorMain.current?.scrollIntoView?.({ block: "start" }),
        );
      if (options.create) {
        setCreation(null);
        setNotice(
          options.create === "folder"
            ? "分类的介绍页已打开，保存后分类就建好了。"
            : "笔记草稿已打开，保存后写入仓库。",
        );
        requestAnimationFrame(() => {
          if (alive.current) textarea.current?.focus();
        });
      }
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy(false);
    }
  }
  async function searchText(query: string) {
    setBusy(true);
    setError("");
    try {
      const result = await api<{
        items: { path: string; title: string; snippet: string }[];
      }>(
        "/api/admin/repository/search?" +
          new URLSearchParams({ query, limit: "20" }),
      );
      setSearch({ query, items: result.items });
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy(false);
    }
  }
  async function restoreDraft(draft: LocalDraft) {
    if (
      dirty &&
      !(await confirm({
        title: "恢复本机草稿？",
        description:
          "当前编辑框的修改将被所选草稿替换。请先保存需要保留的内容。",
        confirmLabel: "恢复草稿",
      }))
    )
      return;
    setBusy(true);
    setError("");
    try {
      const current = await api<RepositoryFile>(
        "/api/admin/repository/file?" +
          new URLSearchParams({ path: draft.path }),
      );
      if (!alive.current) return;
      if (current.source === null && !current.expectedAbsence)
        throw new Error("unreadable file");
      const restored = recoveredFile(current, draft);
      recovery.discard();
      recovery.adopt(draft);
      setFile(restored);
      setSource(draft.source);
      setPath(draft.path);
      setConflict(restored !== current);
      setNotice(
        restored === current
          ? "草稿已恢复到编辑框，请预览后保存。"
          : "草稿已恢复，但仓库文件已有变化。原版本检查已保留，请先复制需要的修改并重新读取文件。",
      );
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }
  async function prepareIdentity() {
    setBusy(true);
    setError("");
    setNotice("");
    const original = source;
    try {
      const draft = await api<{ source: string; articleId: string }>(
        "/api/admin/repository/article-identity",
        { method: "POST", body: { path, source: original } },
      );
      if (!alive.current) return;
      setSource((current) => (current === original ? draft.source : current));
      setNotice(
        draft.source === original
          ? "文章已有 ID，改名或移动时请保留；复制为另一篇文章时请换用新 ID。"
          : "互动标识已加入草稿，点击保存后写入仓库。公开且标识唯一时生效，改名或移动时请保留。",
      );
    } catch (failure) {
      setError(
        failure instanceof ApiError && failure.status === 400
          ? "无法启用文章互动。请检查元数据格式、现有 id 是否为小写 UUID，以及文件是否超过大小限制。草稿已保留。"
          : message(failure),
      );
    } finally {
      setBusy(false);
    }
  }
  async function refreshPublicPage(saved: RepositoryFile) {
    if (!saved.commit || saved.expectedAbsence) return;
    const request = ++pageRequest.current;
    setPagePending(true);
    try {
      const result = await api<RepositoryFile>(
        "/api/admin/repository/file?" +
          new URLSearchParams({ path: saved.path, commit: saved.commit }),
      );
      if (!alive.current || request !== pageRequest.current) return;
      setFile((current) =>
        current?.path === saved.path &&
        current.commit === saved.commit &&
        current.revision === saved.revision
          ? {
              ...current,
              publicScope: result.publicScope,
              publicPage: result.publicPage,
            }
          : current,
      );
    } catch {
      // The write acknowledgement remains authoritative when this separate read fails.
    } finally {
      if (alive.current && request === pageRequest.current)
        setPagePending(false);
    }
  }

  async function save(target = file?.path, remove = false) {
    if (!file || !target) return;
    if (unreadable && !remove) {
      setError("无法读取原文件，不能覆盖或移动它。");
      return;
    }
    setBusy(true);
    setNotice("");
    setError("");
    setConflict(false);
    try {
      const result = await saveRepositoryFile(
        api,
        file,
        target,
        source,
        remove,
      );
      recovery.discard();
      if (remove) {
        pageRequest.current++;
        setPagePending(false);
        setFile(null);
        setSource("");
        setPath("");
        navigate("", folder);
      } else {
        const saved: RepositoryFile = {
          ...file,
          path: target,
          commit: result.commit,
          source,
          revision: result.revisions[target],
          expectedAbsence: false,
          publicPage: null,
        };
        setFile(saved);
        setPath(target);
        navigate(target, folder, true);
        void refreshPublicPage(saved);
      }
      setNotice(
        !result.committed
          ? "内容没有变化，无需创建新版本。"
          : result.snapshotUpdated
            ? remove
              ? "文件已删除。"
              : "已保存。"
            : "文件已保存，公开页面暂时无法更新。请稍后查看。",
      );
      try {
        await reloadTree();
      } catch {
        setNotice("文件操作已确认，但目录暂时无法刷新。请稍后手动刷新目录。");
      }
    } catch (error) {
      setError(message(error));
      setConflict(
        error instanceof ApiError && [0, 409, 503].includes(error.status),
      );
    } finally {
      setBusy(false);
    }
  }
  function resolvePreview() {
    setPreviewVersion((value) => value + 1);
  }
  function chooseMove(source: string, commit: string, trigger: HTMLElement) {
    if (dirty) {
      setError("有未保存的修改，请先保存，再移动文件或文件夹。");
      return;
    }
    setError("");
    setMoveSelection({
      source,
      commit,
      returnFocus: trigger,
    });
  }
  async function switchVisibility() {
    if (!file?.commit || file.expectedAbsence || dirty || busy || unreadable)
      return;
    const publishing = contentRoot(file.path) === "private";
    const destination = inContentRoot(
      file.path,
      publishing ? "public" : "private",
    );
    if (
      !(await confirm({
        title: publishing ? "发布这篇内容？" : "撤回为私有草稿？",
        description: publishing
          ? `将已保存的内容移到「${destination}」。只有网站开启、符合发布规则且账号未受限时才会公开；需要一起发布的媒体请通过移动文件夹处理。`
          : `将已保存的内容移到「${destination}」，停止通过此文章提供公开内容。其他公开引用仍可能提供相同媒体，已被他人保存的副本无法撤回。`,
        confirmLabel: publishing ? "发布" : "撤回为草稿",
      }))
    )
      return;
    try {
      await move(destination, {
        source: file.path,
        commit: file.commit,
        returnFocus: null,
      });
    } catch {
      // The shared move path retains its actionable failure; confirmation never retries it.
    }
  }
  async function move(destination: string, selection = moveSelection) {
    if (!selection || dirty) return false;
    setBusy(true);
    setError("");
    setNotice("");
    setConflict(false);
    try {
      const result = await api<PatchResult>("/api/admin/repository/move", {
        method: "POST",
        body: {
          baseCommit: selection.commit,
          source: selection.source,
          destination,
        },
      });
      if (!alive.current) return true;
      const nextFolder =
        folder === selection.source || folder.startsWith(selection.source + "/")
          ? destination + folder.slice(selection.source.length)
          : folder;
      setSearch(null);
      let notice = result.snapshotUpdated
        ? "已移动，相关链接已更新。"
        : "已移动，公开页面暂时无法更新。";
      if (file) {
        const nextPath =
          file.path === selection.source ||
          file.path.startsWith(selection.source + "/")
            ? destination + file.path.slice(selection.source.length)
            : file.path;
        setFile(null);
        setSource("");
        setPath("");
        try {
          const current = await api<RepositoryFile>(
            "/api/admin/repository/file?" +
              new URLSearchParams({ path: nextPath }),
          );
          if (!alive.current) return true;
          recovery.opened(current);
          setFile(current);
          setSource(current.source ?? "");
          setPath(current.path);
          navigate(current.path, nextFolder, true);
        } catch {
          navigate("", nextFolder, true);
          notice += " 当前文件未能重新读取，请刷新后打开。";
        }
      } else navigate("", nextFolder, true);
      try {
        await reloadTree();
      } catch {
        notice += " 目录未能刷新，请稍后刷新。";
      }
      setNotice(notice);
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setMoveSelection(null);
        setFile(null);
        setSource("");
        setPath("");
        setSearch(null);
        navigate("", navigationFolder(folder), true);
        try {
          await reloadTree();
          setError(
            "仓库内容已改变，目录已刷新。请重新选择要移动的文件或文件夹。",
          );
        } catch {
          setError(
            "仓库内容已改变，目录未能刷新。请刷新目录后重新选择要移动的内容。",
          );
        }
        return false;
      }
      setError(message(error));
      setConflict(
        error instanceof ApiError && [0, 409, 503].includes(error.status),
      );
      throw error;
    } finally {
      setBusy(false);
    }
  }
  function insertion(): ImageInsertion {
    return {
      start: textarea.current?.selectionStart ?? source.length,
      end: textarea.current?.selectionEnd ?? source.length,
      source,
    };
  }
  function receiveImage(files: File[]) {
    if (
      busy ||
      !writable ||
      unreadable ||
      !identity.capabilities.includes("WRITE_PRIVATE")
    ) {
      setError("当前无法上传图片，请等待正在进行的操作完成并确认写入权限。");
      return;
    }
    try {
      setIncomingImage(imageUpload(files, true, insertion()));
      setError("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : message(failure));
    }
  }
  function insert(markdown: string, position?: ImageInsertion) {
    if (position && currentSource.current !== position.source) return false;
    const start = position?.start ?? textarea.current?.selectionStart;
    const end = position?.end ?? textarea.current?.selectionEnd;
    setNotice("");
    setSource((current) =>
      position && current !== position.source
        ? current
        : current.slice(0, start ?? current.length) +
          "\n" +
          markdown +
          "\n" +
          current.slice(end ?? current.length),
    );
    return true;
  }
  return (
    <div className="editor-layout" ref={editorRoot} tabIndex={-1}>
      {exportSelection && (
        <ExportDialog
          {...exportSelection}
          fallbackFocus={editorRoot.current}
          onClose={() => setExportSelection(null)}
        />
      )}
      {moveSelection && (
        <FolderPicker
          {...moveSelection}
          fallbackFocus={editorRoot.current}
          canPublish={identity.capabilities.includes("PUBLISH")}
          canWritePrivate={identity.capabilities.includes("WRITE_PRIVATE")}
          onClose={() => setMoveSelection(null)}
          onMove={move}
        />
      )}
      <aside className="file-sidebar">
        <div className="sidebar-title">
          <h2>全部文件</h2>
          <button
            type="button"
            className="text-button"
            disabled={busy || !tree?.commit}
            onClick={(event) =>
              setExportSelection({
                source: "",
                returnFocus: event.currentTarget,
              })
            }
          >
            导出…
          </button>
          <button
            className="text-button"
            disabled={busy}
            onClick={() =>
              void reloadTree().catch((error) => setError(message(error)))
            }
          >
            刷新
          </button>
        </div>
        <DraftLibrary
          identity={identity}
          disabled={busy}
          onOpen={(draftPath) => void open(draftPath)}
        />
        <section className="content-creation" aria-label="新建位置">
          <p className="selected-folder">
            新建到：{folderLocation(inContentRoot(folder, "private"))}
          </p>
          {folder && (
            <button
              type="button"
              className="text-button"
              disabled={busy}
              onClick={() => navigate(file?.path ?? "", "")}
            >
              回到最外层
            </button>
          )}
          <div className="creation-actions">
            {(["note", "folder"] as const).map((kind) => (
              <button
                key={kind}
                type="button"
                className="button-secondary"
                disabled={
                  busy || !identity.capabilities.includes("WRITE_PRIVATE")
                }
                onClick={(event) => {
                  creationTrigger.current = event.currentTarget;
                  setCreation(kind);
                }}
              >
                {kind === "note" ? "新建笔记" : "新建分类"}
              </button>
            ))}
          </div>
          {creation && (
            <form
              className="open-path"
              key={creation}
              onSubmit={(event) => {
                event.preventDefault();
                try {
                  const target = privateCreationPath(
                    folder,
                    String(new FormData(event.currentTarget).get("name") ?? ""),
                    creation,
                  );
                  void open(target, {
                    create: creation,
                    folder: parentDirectory(target),
                  });
                } catch (failure) {
                  setError(message(failure));
                }
              }}
            >
              <label>
                {creation === "note" ? "笔记名称" : "分类名称"}
                <input
                  name="name"
                  required
                  maxLength={255}
                  autoFocus
                  disabled={busy}
                  placeholder={
                    creation === "note" ? "例如：新的灵感" : "例如：旅行记录"
                  }
                />
              </label>
              <p className="muted">
                默认创建在 {inContentRoot(folder, "private")}/，保存后生效。
              </p>
              <button disabled={busy}>
                准备{creation === "note" ? "笔记" : "分类"}
              </button>
              <button
                type="button"
                className="text-button"
                disabled={busy}
                onClick={() => {
                  setCreation(null);
                  creationTrigger.current?.focus();
                }}
              >
                取消
              </button>
            </form>
          )}
        </section>
        <nav className="file-tree" aria-label="仓库文件">
          {tree && (
            <FileTree
              commit={tree.commit}
              selected={file?.path}
              selectedFolder={folder}
              onSelectFolder={(selectedFolder) =>
                navigate(file?.path ?? "", selectedFolder)
              }
              busy={busy}
              onOpen={(path) => void open(path)}
              onMove={writable ? chooseMove : undefined}
              onExport={(source, returnFocus) =>
                setExportSelection({ source, returnFocus })
              }
            />
          )}
        </nav>
        <details className="filename-details">
          <summary>按文件名查找</summary>
          <FilenameSearch
            busy={busy}
            commit={tree?.commit ?? null}
            onOpen={(path) => void open(path)}
          />
        </details>
        <details className="body-search" open={search !== null || undefined}>
          <summary>搜索笔记正文</summary>
          <form
            className="open-path private-search"
            onSubmit={(event) => {
              event.preventDefault();
              void searchText(
                String(new FormData(event.currentTarget).get("query")),
              );
            }}
          >
            <label>
              <span className="sr-only">搜索笔记正文</span>
              <input
                name="query"
                required
                maxLength={200}
                placeholder="包括有权读取的私有内容"
              />
            </label>
            <button className="button-secondary" disabled={busy}>
              搜索正文
            </button>
          </form>
        </details>
        {search && (
          <section className="private-results">
            <div className="sidebar-title">
              <span>搜索结果 · 最多 20 篇</span>
              <button className="text-button" onClick={() => setSearch(null)}>
                收起
              </button>
            </div>
            {search.items.map((item) => (
              <button
                key={item.path}
                disabled={busy}
                onClick={() => void open(item.path)}
              >
                <strong>
                  <SearchHighlight text={item.title} query={search.query} />
                </strong>
                <small>
                  <SearchHighlight text={item.snippet} query={search.query} />
                </small>
              </button>
            ))}
            {!search.items.length && (
              <p className="muted">没有找到匹配内容。</p>
            )}
          </section>
        )}
        <details className="advanced-path">
          <summary>高级：完整路径</summary>
          <form
            className="open-path"
            onSubmit={(event) => {
              event.preventDefault();
              const target = String(
                new FormData(event.currentTarget).get("path"),
              );
              void open(target);
            }}
          >
            <label>
              打开或新建路径
              <input
                name="path"
                defaultValue="private/"
                placeholder="private/笔记/新文章.md"
                required
                maxLength={255}
              />
            </label>
            <button className="button-secondary" disabled={busy}>
              打开路径
            </button>
          </form>
        </details>
        {tree?.diagnostics.length ? (
          <details className="diagnostics">
            <summary>仓库诊断 · {tree.diagnostics.length}</summary>
            {tree.diagnostics.map((diagnostic, index) => (
              <div key={index}>
                <strong>{diagnostic.path}</strong>
                <DiagnosticMessage diagnostic={diagnostic} />
              </div>
            ))}
          </details>
        ) : null}
      </aside>
      <section className="editor-main" ref={editorMain}>
        {error && (
          <div className="notice danger" role="alert">
            {error}
            {conflict && file && (
              <p>
                编辑框中的内容仍然保留。先复制需要保留的修改，再
                <button
                  className="text-button"
                  onClick={() => void open(file.path)}
                >
                  重新读取文件
                </button>
                。
              </p>
            )}
          </div>
        )}
        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        {file && (
          <DraftRecovery
            drafts={recovery.available}
            disabled={busy || !writable || unreadable}
            onRestore={(draft) => void restoreDraft(draft)}
            onForget={async (draft) => {
              if (
                await confirm({
                  title: "删除这份本机草稿？",
                  description: "这份未保存正文将从浏览器移除，仓库文件不变。",
                  confirmLabel: "删除本机草稿",
                })
              )
                await recovery.forget(draft);
            }}
          />
        )}
        {recovery.status && (
          <p className="muted" role="status">
            {recovery.status}
          </p>
        )}
        {file ? (
          <>
            <div className="editor-toolbar">
              <label className="path-label">
                文件路径
                <input
                  value={path}
                  onChange={(event) => {
                    setNotice("");
                    setPath(event.target.value);
                  }}
                  disabled={!writable || busy || !file.expectedAbsence}
                  maxLength={255}
                />
              </label>
              <div className="editor-actions">
                {identity.capabilities.includes("READ_PRIVATE") &&
                  !unreadable &&
                  path === file.path &&
                  file.commit && (
                    <button
                      type="button"
                      className="button-secondary"
                      disabled={busy}
                      onClick={(event) =>
                        setHistorySelection({
                          file,
                          returnFocus: event.currentTarget,
                        })
                      }
                    >
                      历史版本
                    </button>
                  )}
                {contentRoot(path) && (
                  <button
                    type="button"
                    className="button-secondary"
                    disabled={
                      busy ||
                      dirty ||
                      unreadable ||
                      file.expectedAbsence ||
                      !file.commit ||
                      !["READ_PRIVATE", "WRITE_PRIVATE", "PUBLISH"].every(
                        (capability) =>
                          identity.capabilities.includes(capability),
                      )
                    }
                    title={
                      dirty || file.expectedAbsence
                        ? "请先保存草稿，再发布或撤回。"
                        : "需要私有读取、写入和发布权限。"
                    }
                    onClick={() => void switchVisibility()}
                  >
                    {contentRoot(path) === "public" ? "撤回为草稿" : "发布"}
                  </button>
                )}
                <button
                  type="button"
                  className="button-secondary"
                  disabled={!writable || busy || unreadable || !path}
                  onClick={() => void prepareIdentity()}
                >
                  启用文章互动
                </button>
                {!file.expectedAbsence && file.commit && (
                  <button
                    type="button"
                    className="button-secondary"
                    disabled={!writable || busy || dirty}
                    onClick={(event) =>
                      chooseMove(file.path, file.commit!, event.currentTarget)
                    }
                  >
                    移动…
                  </button>
                )}
                {contentRoot(path) && (
                  <span
                    className="visibility-badge"
                    data-root={contentRoot(path)}
                  >
                    <Icon
                      name={contentRoot(path) === "public" ? "globe" : "lock"}
                    />
                    {contentRoot(path) === "public" ? "公开" : "私密"}
                  </span>
                )}
                <span
                  className="save-state"
                  data-state={
                    file.expectedAbsence ? "new" : dirty ? "dirty" : "saved"
                  }
                >
                  {file.expectedAbsence
                    ? "新文件"
                    : dirty
                      ? "有未保存修改"
                      : "已保存"}
                </span>
                <button
                  disabled={
                    !writable ||
                    busy ||
                    unreadable ||
                    !path ||
                    (!file.expectedAbsence &&
                      source === file.source &&
                      path === file.path)
                  }
                  title="保存（在正文中可按 Ctrl+S）"
                  onClick={() => void save(path)}
                >
                  {busy ? "处理中…" : "保存"}
                </button>
              </div>
            </div>
            <EditorPublicPage
              file={file}
              path={path}
              dirty={dirty}
              pending={busy || pagePending}
              onRefresh={() => void refreshPublicPage(file)}
            />
            {historySelection?.file === file &&
              !unreadable &&
              path === file.path && (
                <HistoryDialog
                  file={file}
                  currentSource={source}
                  dirty={dirty}
                  writable={writable}
                  returnFocus={historySelection.returnFocus}
                  onClose={() => setHistorySelection(null)}
                  onRestore={(historicalSource, commit) => {
                    setSource(historicalSource);
                    setHistorySelection(null);
                    setNotice(
                      `已将 ${commit.slice(0, 8)} 的正文放入编辑框。请预览后保存，保存将生成新版本。`,
                    );
                  }}
                />
              )}
            {file.source === null && !file.expectedAbsence ? (
              <p className="notice danger">
                这个文件无法作为 UTF-8 文本读取。请查看诊断，不要覆盖原文件。
              </p>
            ) : (
              <>
                <div className="view-tabs">
                  <span className="segmented" role="group" aria-label="视图">
                    <button
                      type="button"
                      aria-pressed={view === "write"}
                      onClick={() => setView("write")}
                    >
                      编辑
                    </button>
                    <button
                      type="button"
                      aria-pressed={view === "preview"}
                      onClick={() => setView("preview")}
                    >
                      预览
                    </button>
                    <button
                      type="button"
                      aria-pressed={view === "split"}
                      onClick={() => setView("split")}
                    >
                      并排
                    </button>
                  </span>
                  <button className="text-button" onClick={resolvePreview}>
                    更新图片预览
                  </button>
                </div>
                <div className={"editor-panes view-" + view}>
                  <label className="source-pane">
                    <span className="sr-only">Markdown 正文</span>
                    <textarea
                      ref={textarea}
                      value={source}
                      onChange={(event) => {
                        setNotice("");
                        setSource(event.target.value);
                      }}
                      onKeyDown={(event) => {
                        if (
                          (event.ctrlKey || event.metaKey) &&
                          event.key === "s"
                        ) {
                          event.preventDefault();
                          if (writable && !busy && dirty) void save(path);
                        }
                      }}
                      onPaste={(event) => {
                        const files = Array.from(event.clipboardData.files);
                        if (!files.length) return;
                        event.preventDefault();
                        receiveImage(files);
                      }}
                      onDragOver={(event) => {
                        if (event.dataTransfer.types.includes("Files"))
                          event.preventDefault();
                      }}
                      onDrop={(event) => {
                        const files = Array.from(event.dataTransfer.files);
                        if (!files.length) return;
                        event.preventDefault();
                        receiveImage(files);
                      }}
                      spellCheck={false}
                      readOnly={!writable || busy}
                    />
                  </label>
                  <div className="preview-pane">
                    {previewError ? (
                      <p className="notice danger">{previewError}</p>
                    ) : preview.body === undefined ? (
                      <p className="muted">正在更新预览…</p>
                    ) : (
                      <>
                        <Markdown
                          source={preview.body}
                          images={preview.images}
                          links={preview.links}
                          downloads={preview.downloads}
                          playback={preview.playback}
                          preview
                        />
                        <Gallery
                          items={preview.gallery}
                          status={preview.galleryStatus}
                          preview
                        />
                      </>
                    )}
                  </div>
                </div>
                {writable && !working && (
                  <AssetPicker
                    key={path}
                    path={path}
                    commit={file.commit}
                    canUpload={identity.capabilities.includes("WRITE_PRIVATE")}
                    canReadPrivate={identity.capabilities.includes(
                      "READ_PRIVATE",
                    )}
                    onInsert={insert}
                    incoming={incomingImage}
                    onConsumed={() => setIncomingImage(null)}
                    onUploading={setUploading}
                    insertion={insertion}
                  />
                )}
                {identity.capabilities.includes("WRITE_PRIVATE") && (
                  <p className="muted">
                    可在正文中粘贴或拖入一张图片，最多 16
                    MiB；上传后保存文章才会写入内容。
                  </p>
                )}
              </>
            )}
            <div className="editor-foot">
              <span>
                公开范围内的修改会更新网站，已经被他人保存的副本无法撤回。
              </span>
              {!file.expectedAbsence && (
                <button
                  className="text-button danger-text"
                  disabled={!writable || busy}
                  onClick={async () => {
                    if (
                      await confirm({
                        title: "删除文件？",
                        description: `将从仓库中删除「${file.path}」。尚未保存的修改也会丢失。`,
                        confirmLabel: "确认删除",
                      })
                    )
                      void save(file.path, true);
                  }}
                >
                  删除文件
                </button>
              )}
            </div>
            {file.diagnostics.map((diagnostic, index) => (
              <div className="notice" key={index}>
                <DiagnosticMessage diagnostic={diagnostic} />
              </div>
            ))}
          </>
        ) : (
          <NoteLibrary
            tree={tree}
            busy={busy}
            canWrite={identity.capabilities.includes("WRITE_PRIVATE")}
            onOpen={(target) => void open(target)}
            onCreate={(kind, trigger) => {
              creationTrigger.current = trigger;
              setCreation(kind);
            }}
          />
        )}
      </section>
    </div>
  );
}
