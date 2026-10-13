export type GameBundle = {
  protocol: 1;
  source: string;
  presentation: string | null;
  resources: Record<string, { mediaType: string; data: string }>;
};

export type GamePackage = {
  workspaceId: string;
  articleId: string;
  title: string;
  version: string;
  help: string;
  bundle: GameBundle;
};

export type GameSave = {
  saveId: string;
  revision: string;
  workspaceId: string;
  articleId: string;
  title: string;
  packageVersion: string;
  result: { state: unknown };
};

export type GameSaveIndex = {
  accountId: string;
  items: (Pick<
    GameSave,
    "saveId" | "revision" | "workspaceId" | "articleId" | "title"
  > & {
    status: string;
  })[];
  nextCreationRequest: string;
};

export type GameUpload = {
  accountId: string;
  saveId: string | null;
  creationRequest: string | null;
  expectedRevision: string | null;
  space: string;
  articleId: string;
  packageVersion: string;
  state: unknown;
};

export function sameGame(
  save: Pick<GameSave, "workspaceId" | "articleId">,
  game: GamePackage,
) {
  return (
    save.workspaceId === game.workspaceId && save.articleId === game.articleId
  );
}

export function gamePackageUrl(space: string, article: string) {
  return `/api/public/games/spaces/${encodeURIComponent(space)}/articles/${encodeURIComponent(article)}`;
}
