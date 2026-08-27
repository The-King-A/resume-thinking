# Resume Matching Platform Web App

The Vue client talks to the Java API at `http://127.0.0.1:8080` by default.
For a different local API origin, copy `.env.example` to `.env.local` and set
`VITE_API_BASE_URL` before starting Vite:

```powershell
Copy-Item .env.example .env.local
pnpm run dev
```

The value should be the Java origin only (for example,
`http://localhost:8080`). The client appends its `/api/v1/...` paths. When the
frontend runs at `http://localhost:5173` or `http://127.0.0.1:5173`, keep the
matching origins in the Java `APP_CORS_ALLOWED_ORIGINS` setting.
