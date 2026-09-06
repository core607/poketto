export type GalleryStatus = "COMPLETE" | "PARTIAL" | "UNAVAILABLE";
export type Snapshot = {
  commit: string | null;
  verifiedAt: string;
  expiresAt: string;
};
export type ArticleSummary = {
  route: string;
  title: string;
  tags: string[];
  createdAt: string;
  updatedAt: string;
  snippet: string;
};
export type ArticlePage = Snapshot & {
  items: ArticleSummary[];
  total: number;
  offset: number;
  limit: number;
};
export type Article = Snapshot &
  Omit<ArticleSummary, "snippet"> & {
    body: string;
    folderPage: boolean;
    images?: Record<string, string>;
    links?: Record<string, string>;
    gallery?: { src: string; alt: string }[];
    galleryStatus: GalleryStatus;
  };
export type TagPage = Snapshot & {
  tags: string[];
  total: number;
  offset: number;
  limit: number;
};
export type RepositoryFile = {
  commit: string | null;
  path: string;
  source: string | null;
  revision: string | null;
  expectedAbsence: boolean;
  diagnostics: Diagnostic[];
  images?: Record<string, string>;
};
export type Diagnostic = { path: string; code: string; message: string };
export type RepositoryTree = {
  commit: string | null;
  entries: { path: string; title: string }[];
  diagnostics: Diagnostic[];
};
export type PatchResult = {
  commit: string;
  committed: boolean;
  snapshotUpdated: boolean;
  revisions: Record<string, string | null>;
};
