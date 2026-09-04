export class ApiError extends Error {
  constructor(
    public status: number,
    public detail: string,
  ) {
    super(detail);
  }
}
type Csrf = { headerName: string; token: string };
export async function api<T>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    form?: URLSearchParams;
    multipart?: FormData;
    headers?: Record<string, string>;
  } = {},
): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = {
    Accept: "application/json",
    ...options.headers,
  };
  if (method !== "GET") {
    const csrfResponse = await fetch("/api/auth/csrf", {
      credentials: "same-origin",
      cache: "no-store",
      signal: AbortSignal.timeout(30000),
    });
    if (!csrfResponse.ok)
      throw new ApiError(csrfResponse.status, "无法验证当前会话，请重新登录。");
    const csrf = (await csrfResponse.json()) as Csrf;
    headers[csrf.headerName] = csrf.token;
  }
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.form)
    headers["Content-Type"] = "application/x-www-form-urlencoded";
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body:
        options.multipart ??
        options.form ??
        (options.body !== undefined ? JSON.stringify(options.body) : undefined),
      credentials: "same-origin",
      cache: "no-store",
      signal: AbortSignal.timeout(30000),
    });
  } catch {
    throw new ApiError(
      0,
      method === "GET"
        ? "连接中断，请稍后重试。"
        : "连接中断，无法确认操作结果。请先重新读取，再决定是否重试。",
    );
  }
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: "输入格式有误，请检查后重试。",
      401: "登录信息无效或会话已过期。",
      403: "当前身份无权执行此操作。",
      404: "没有找到这项内容。",
      409: "操作与当前状态冲突，请重新读取后核对。",
      429: "操作过于频繁，请稍后再试。",
      503: "服务暂时不可用。写入结果可能尚未确认，请先重新读取。",
    };
    throw new ApiError(
      response.status,
      messages[response.status] ?? "操作未能完成，请稍后重试。",
    );
  }
  return response.status === 204
    ? (undefined as T)
    : ((await response.json()) as T);
}
