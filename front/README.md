# 简历匹配平台网页应用

Vue 客户端默认通过 `http://127.0.0.1:8080` 调用 Java API。如果本地 API 使用
其他来源地址，请在启动 Vite 前将 `.env.example` 复制为 `.env.local`，并设置
`VITE_API_BASE_URL`：

```powershell
Copy-Item .env.example .env.local
pnpm run dev
```

该值只能填写 Java 来源地址（例如 `http://localhost:8080`）。客户端会自行拼接
`/api/v1/...` 路径。当前端运行在 `http://localhost:5173` 或
`http://127.0.0.1:5173` 时，请在 Java 的 `APP_CORS_ALLOWED_ORIGINS` 设置中
保留对应来源地址。
