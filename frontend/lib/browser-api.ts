export class ApiError extends Error {
  constructor(
    public status: number,
    public detail: string,
    public code?: string,
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
    timeoutMs?: number;
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
      signal: AbortSignal.timeout(options.timeoutMs ?? 30000),
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
    const problem = await response.json().catch(() => null);
    const code =
      typeof problem?.code === "string" && problem.code.length <= 80
        ? problem.code
        : undefined;
    if (code === "LAST_ADMINISTRATOR")
      throw new ApiError(
        409,
        "至少需要保留一位站点管理员。请先指定其他管理员。",
        code,
      );
    const identityErrors: Record<string, string> = {
      INVALID_CHALLENGE:
        "验证码无效、已过期或已使用。请检查邮箱与验证码，必要时重新获取。",
      EMAIL_IN_USE: "该邮箱已绑定账号。请登录原账号，或使用其他邮箱。",
      IDENTITY_IN_USE:
        "该 Google 身份已绑定其他账号，或当前账号已绑定另一 Google 身份。请先核对登录方式。",
      DELIVERY_UNAVAILABLE: "验证码暂时无法发送，请稍后重试。",
      LAST_LOGIN_METHOD: "至少需要保留一种登录方式。请先绑定其他登录方式。",
    };
    if (code && identityErrors[code])
      throw new ApiError(response.status, identityErrors[code], code);
    if (response.status === 503 && code === "REPOSITORY_RECONNECT")
      throw new ApiError(
        response.status,
        "仓库连接需要恢复。请由原授权的空间主人前往空间的“存储位置”核对并恢复连接。",
        code,
      );
    if (response.status === 503 && code === "REPOSITORY_RETRY")
      throw new ApiError(
        response.status,
        method === "GET"
          ? "仓库访问暂时不可用，请稍后重试。"
          : "仓库访问暂时不可用。请先重新读取并核对原操作，再决定是否重试。",
        code,
      );
    if (response.status === 400) {
      if (problem?.code === "INVALID_INVITATION")
        throw new ApiError(
          400,
          "空间邀请码无效、已过期或已使用。请向空间主人获取新的空间邀请码。",
        );
      if (problem?.code === "MOVE_UNPUBLISHABLE_DEPENDENCY")
        throw new ApiError(
          400,
          "移动后公开文档会引用私有或不支持的内容。请检查依赖，或连同所需媒体一起移动文件夹。",
        );
    }
    const messages: Record<number, string> = {
      400: "输入格式有误，请检查后重试。",
      401: "登录信息无效或会话已过期。",
      403: "当前身份无权执行此操作。",
      404: "没有找到这项内容。",
      409: "操作与当前状态冲突，请重新读取后核对。",
      429: "操作过于频繁，请稍后再试。",
      503:
        method === "GET"
          ? "服务暂时不可用，请稍后重试。"
          : "服务暂时不可用。写入结果可能尚未确认，请先重新读取。",
    };
    throw new ApiError(
      response.status,
      messages[response.status] ?? "操作未能完成，请稍后重试。",
      code,
    );
  }
  return response.status === 204
    ? (undefined as T)
    : ((await response.json()) as T);
}
