// SiliconFlow 免费对话代理：key 存于 Cloudflare 加密环境变量，从不进入客户端。
// 应用 → 本 Worker（注入 key）→ api.siliconflow.cn；key 永不离开 Cloudflare。
// 只放行白名单免费模型；OpenAI 兼容，流式（SSE）原样回传。
const ALLOWED_MODELS = new Set([
  "Qwen/Qwen2.5-7B-Instruct",
  "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
]);
const UPSTREAM = "https://api.siliconflow.cn/v1/chat/completions";

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: cors() });
    }
    const url = new URL(request.url);
    if (!url.pathname.endsWith("/chat/completions")) {
      return new Response("ok", { status: 200, headers: cors() });
    }
    if (request.method !== "POST") {
      return new Response("Method Not Allowed", { status: 405, headers: cors() });
    }
    const key = env.SILICONFLOW_API_KEY;
    if (!key) {
      return new Response("proxy not configured", { status: 500, headers: cors() });
    }
    let body;
    try {
      body = await request.json();
    } catch {
      return new Response("bad json", { status: 400, headers: cors() });
    }
    if (!ALLOWED_MODELS.has(body.model)) {
      return new Response(
        JSON.stringify({ error: { message: "model not allowed in free proxy: " + body.model } }),
        { status: 403, headers: cors() },
      );
    }
    // 转发给 SiliconFlow，key 由服务器注入（忽略客户端传的 Authorization）
    const upstream = await fetch(UPSTREAM, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + key,
      },
      body: JSON.stringify(body),
    });
    // 流式（SSE）原样回传
    const ct = upstream.headers.get("Content-Type") || "application/json";
    return new Response(upstream.body, {
      status: upstream.status,
      headers: { ...cors(), "Content-Type": ct },
    });
  },
};

function cors() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
  };
}
