import type { RepositoryFile } from "./types";

const PREFIX = "poketto:draft:v1:";
export const MAX_DRAFTS = 20;
export const MAX_DRAFT_BYTES = 1024 * 1024;
export const MAX_STORE_BYTES = 2 * MAX_DRAFT_BYTES;

export type LocalDraft = {
  version: 1;
  id: string;
  accountId: string;
  workspaceId: string;
  path: string;
  source: string;
  updatedAt: number;
  baseline: Pick<
    RepositoryFile,
    "path" | "commit" | "revision" | "expectedAbsence"
  >;
};
export type DraftStorage = Pick<
  Storage,
  "length" | "key" | "getItem" | "setItem" | "removeItem"
>;

function text(value: unknown, maximum: number): value is string {
  return typeof value === "string" && value.length <= maximum;
}
function identity(value: unknown): value is string {
  return text(value, 128) && /^[a-zA-Z0-9-]+$/.test(value);
}
function path(value: unknown): value is string {
  return (
    text(value, 1024) &&
    value.length > 0 &&
    !value.startsWith("/") &&
    !value.includes("\\") &&
    !value.split("/").some((part) => !part || part === "." || part === "..")
  );
}
function optionalRevision(value: unknown): value is string | null {
  return value === null || text(value, 128);
}
function bytes(value: string) {
  return new TextEncoder().encode(value).length;
}
function parse(raw: string | null): LocalDraft | null {
  if (!raw || raw.length > MAX_STORE_BYTES + 16_384) return null;
  try {
    const draft = JSON.parse(raw);
    const base = draft?.baseline;
    if (
      draft?.version !== 1 ||
      !identity(draft.id) ||
      !identity(draft.accountId) ||
      !identity(draft.workspaceId) ||
      !path(draft.path) ||
      !text(draft.source, MAX_DRAFT_BYTES) ||
      bytes(draft.source) > MAX_DRAFT_BYTES ||
      !Number.isSafeInteger(draft.updatedAt) ||
      draft.updatedAt < 0 ||
      !Number.isFinite(new Date(draft.updatedAt).getTime()) ||
      !path(base?.path) ||
      !optionalRevision(base.commit) ||
      !optionalRevision(base.revision) ||
      typeof base.expectedAbsence !== "boolean" ||
      (!base.expectedAbsence && draft.path !== base.path)
    )
      return null;
    return {
      version: 1,
      id: draft.id,
      accountId: draft.accountId,
      workspaceId: draft.workspaceId,
      path: draft.path,
      source: draft.source,
      updatedAt: draft.updatedAt,
      baseline: {
        path: base.path,
        commit: base.commit,
        revision: base.revision,
        expectedAbsence: base.expectedAbsence,
      },
    };
  } catch {
    return null;
  }
}
function key(draft: Pick<LocalDraft, "accountId" | "workspaceId" | "id">) {
  return PREFIX + [draft.accountId, draft.workspaceId, draft.id].join(":");
}
function records(storage: DraftStorage) {
  const result: { key: string; raw: string; draft: LocalDraft | null }[] = [];
  for (let index = 0; index < storage.length; index++) {
    const name = storage.key(index);
    if (!name?.startsWith(PREFIX)) continue;
    if (result.length >= MAX_DRAFTS)
      throw new Error("本机草稿数量已达到上限，请先恢复或清理已有草稿。");
    const raw = storage.getItem(name) ?? "";
    const draft = parse(raw);
    result.push({
      key: name,
      raw,
      draft: draft && key(draft) === name ? draft : null,
    });
  }
  return result;
}

/** Callers must establish current workspace/file access before displaying cached source. */
export function localDrafts(
  storage: DraftStorage,
  accountId: string,
  workspaceId: string,
) {
  return records(storage)
    .flatMap(({ draft }) =>
      draft?.accountId === accountId && draft.workspaceId === workspaceId
        ? [draft]
        : [],
    )
    .sort((first, second) => second.updatedAt - first.updatedAt);
}

/** The browser owner serializes writes with Web Locks; failures never evict another draft. */
export function retainDraft(
  storage: DraftStorage,
  draft: LocalDraft,
  generation?: string,
) {
  if (
    generation !== undefined &&
    generation !== draftGeneration(storage, draft.accountId)
  )
    throw new Error("本机草稿已随账号退出清理，请重新登录后继续。");
  const raw = JSON.stringify(draft);
  if (!parse(raw)) throw new Error("草稿格式或大小超出本机恢复范围。");
  const target = key(draft);
  const existing = records(storage).filter((record) => record.key !== target);
  if (existing.length >= MAX_DRAFTS)
    throw new Error("本机草稿数量已达到上限，请先恢复或清理已有草稿。");
  if (
    existing.reduce((size, record) => size + bytes(record.raw), bytes(raw)) >
    MAX_STORE_BYTES
  )
    throw new Error("本机草稿空间已满，请先恢复或清理已有草稿。");
  storage.setItem(target, raw);
}

export function removeDraft(storage: DraftStorage, draft: LocalDraft) {
  storage.removeItem(key(draft));
}

export function removeUnchangedDraft(storage: DraftStorage, draft: LocalDraft) {
  const current = parse(storage.getItem(key(draft)));
  if (JSON.stringify(current) === JSON.stringify(parse(JSON.stringify(draft))))
    removeDraft(storage, draft);
}

export async function withDraftStorage<T>(
  operation: (storage: Storage) => T,
): Promise<T> {
  if (!window.navigator.locks)
    throw new Error("此浏览器不支持本机草稿恢复，请保存或复制未完成的正文。");
  return window.navigator.locks.request("poketto-local-drafts", () =>
    operation(window.localStorage),
  );
}

export function clearAccountDrafts(storage: DraftStorage, accountId: string) {
  if (!identity(accountId)) return;
  const prefix = PREFIX + accountId + ":";
  for (let index = storage.length - 1; index >= 0; index--) {
    const name = storage.key(index);
    if (name?.startsWith(prefix)) storage.removeItem(name);
  }
  storage.setItem("poketto:draft-account:" + accountId, crypto.randomUUID());
}

export function draftGeneration(storage: DraftStorage, accountId: string) {
  return storage.getItem("poketto:draft-account:" + accountId) ?? "";
}

/** Only an unchanged file may adopt a newer unrelated repository commit during recovery. */
export function recoveredFile(
  current: RepositoryFile,
  draft: LocalDraft,
): RepositoryFile {
  const unchanged =
    current.expectedAbsence === draft.baseline.expectedAbsence &&
    current.revision === draft.baseline.revision &&
    current.path === draft.path;
  return unchanged ? current : { ...current, ...draft.baseline };
}
