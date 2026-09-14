export type GalleryStatus = "COMPLETE" | "PARTIAL" | "UNAVAILABLE";
export type Snapshot = {
  commit: string | null;
  verifiedAt: string;
  expiresAt: string;
};
export type DiscoveryPage = {
  batch: string;
  expiresAt: string;
  offset: number;
  limit: number;
  nextOffset: number | null;
  previousOffset: number | null;
  items: {
    space: string;
    spaceName: string;
    authorName: string;
    route: string;
    title: string;
    snippet: string;
    tags: string[];
    createdAt: string;
    folderPage: boolean;
    album: boolean;
    collection: boolean;
    cover: string | null;
  }[];
};
export type ArticleSummary = {
  authorName: string;
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
export type SiteSearchPage = {
  items: { space: string; spaceName: string; document: ArticleSummary }[];
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
    downloads?: Record<string, string>;
    gallery?: { src: string; original: string; alt: string }[];
    galleryStatus: GalleryStatus;
    navigation: CollectionNavigation;
  };
export type ArticleReference = { route: string; title: string };
export type CollectionNavigation = {
  entries: ArticleReference[];
  available: boolean;
  memberships: {
    collection: ArticleReference;
    position: number;
    total: number;
    previous: ArticleReference | null;
    next: ArticleReference | null;
  }[];
};
export type TagPage = Snapshot & {
  tags: string[];
  total: number;
  offset: number;
  limit: number;
};
export type PublicPage = {
  state:
    "UNSAVED" | "PRIVATE" | "WEBSITE_DISABLED" | "UNAVAILABLE" | "AVAILABLE";
  space: string | null;
  route: string | null;
};
export type RepositoryFile = {
  publicScope: boolean;
  // Cleared locally after an acknowledged save until exact-revision metadata is read.
  publicPage: PublicPage | null;
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
export type RepositoryDirectory = {
  commit: string | null;
  path: string;
  expectedAbsence: boolean;
  entries: {
    path: string;
    kind: "FILE" | "DIRECTORY" | "SYMLINK" | "SUBMODULE" | "OTHER";
  }[];
  nextOffset: number | null;
};
