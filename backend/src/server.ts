import express from "express";
import cors from "cors";
import helmet from "helmet";
import rateLimit from "express-rate-limit";
import dotenv from "dotenv";
import {
  DbAdapter,
  defaultDb,
  ensureBackupSchema,
  getRequiredMissingServerEnv,
} from "./db/pool";
import {
  TokenVerifier,
  createAuthMiddleware,
  defaultFirebaseTokenVerifier,
  initFirebaseAdmin,
} from "./middleware/auth";
import { createLockerRouter } from "./routes/lockerRoutes";
import { createBackupRouter } from "./routes/backupRoutes";

dotenv.config();

export interface CreateAppOptions {
  db?: DbAdapter;
  tokenVerifier?: TokenVerifier;
  allowedOrigins?: string[];
  skipSchemaInit?: boolean;
}

const CAPACITOR_APP_ORIGINS = new Set([
  "capacitor://localhost",
  "ionic://localhost",
  "https://localhost",
  "http://localhost",
]);

export function parseAllowedCorsOrigins(raw = process.env.CORS_ALLOWED_ORIGINS || ""): string[] {
  return raw
    .split(",")
    .map((s) => s.trim().replace(/\/+$/, ""))
    .filter((s) => s.length > 0 && s !== "*");
}

export function createApp(options: CreateAppOptions = {}) {
  const db = options.db ?? defaultDb;
  const verifyToken = options.tokenVerifier ?? defaultFirebaseTokenVerifier;
  const allowlist = (options.allowedOrigins ?? parseAllowedCorsOrigins()).map((origin) =>
    origin.trim().replace(/\/+$/, "")
  );

  const { requireFirebaseAuth, requireAuthorizedOwner } = createAuthMiddleware(db, verifyToken);

  const app = express();
  app.set("trust proxy", 1);
  app.use(helmet());

  // Strict CORS origin validation: supports explicit CORS_ALLOWED_ORIGINS + Capacitor WebViews (no wildcard * with credentials)
  app.use(
    cors({
      origin: (origin, callback) => {
        // Native mobile Android OkHttp/server calls do not send a browser Origin header
        if (!origin) {
          callback(null, true);
          return;
        }
        const normalizedOrigin = origin.trim().replace(/\/+$/, "");
        if (allowlist.includes(normalizedOrigin) || CAPACITOR_APP_ORIGINS.has(normalizedOrigin)) {
          callback(null, true);
          return;
        }
        callback(new Error(`Origin '${origin}' rejected by CORS_ALLOWED_ORIGINS policy.`));
      },
      credentials: true,
      methods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
      allowedHeaders: ["Authorization", "Content-Type", "X-Requested-With"],
      exposedHeaders: ["X-File-Sha256", "X-Is-Encrypted-Payload", "X-Key-Version", "Content-Length"],
    })
  );

  // Larger JSON limit specifically for /api/backup/create binary uploads; tight 2mb limit elsewhere
  app.use("/api/backup/create", express.json({ limit: "30mb" }));
  app.use(express.json({ limit: "2mb" }));

  const apiLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 300,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: "Too many requests. Please slow down." },
  });

  const authAndMpinLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 40,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: "Too many authentication or MPIN verification attempts." },
  });

  app.get("/", (_req, res) => {
    res.status(200).json({
      service: "DSK LOCKER Neon + Render Cloud Backup Backend",
      status: "ok",
      health: "/health",
    });
  });

  app.get("/health", async (_req, res) => {
    const missingEnv = getRequiredMissingServerEnv();
    try {
      await db.query("SELECT 1");
      if (!options.skipSchemaInit && db === defaultDb && (process.env.DATABASE_URL || "").trim()) {
        await ensureBackupSchema(db);
      }
      res.status(200).json({
        service: "DSK LOCKER Neon + Render Cloud Backup Backend",
        status: missingEnv.length === 0 ? "ok" : "degraded",
        missingEnv,
      });
    } catch {
      res.status(503).json({
        service: "DSK LOCKER Neon + Render Cloud Backup Backend",
        status: "db_unavailable",
        missingEnv,
      });
    }
  });

  app.use("/api/auth", authAndMpinLimiter);
  app.use("/api/security", authAndMpinLimiter);
  app.use("/api", apiLimiter);
  app.use("/api", createLockerRouter(db, requireFirebaseAuth, requireAuthorizedOwner));
  app.use("/api", createBackupRouter(db, requireFirebaseAuth, requireAuthorizedOwner));

  // Explicit CORS / payload error handler returning structured JSON
  app.use(
    (
      err: Error & { status?: number; type?: string },
      _req: express.Request,
      res: express.Response,
      _next: express.NextFunction
    ) => {
      if (err.type === "entity.too.large") {
        res.status(413).json({ error: "Backup payload exceeds Render upload size limit." });
        return;
      }
      if (err.message?.includes("CORS_ALLOWED_ORIGINS")) {
        res.status(403).json({ error: err.message });
        return;
      }
      res.status(err.status || 500).json({ error: err.message || "Internal server error." });
    }
  );

  return app;
}

async function startServer() {
  const missingEnv = getRequiredMissingServerEnv();
  if (missingEnv.length > 0) {
    console.warn(
      `[DSK LOCKER Backend] Missing required environment variables: ${missingEnv.join(", ")}`
    );
  }
  initFirebaseAdmin();

  const app = createApp();
  const host = "0.0.0.0";
  const port = Number(process.env.PORT || 8080);

  await new Promise<void>((resolve) => {
    app.listen(port, host, () => {
      console.log(`DSK LOCKER Neon Backup Backend listening on http://${host}:${port}`);
      resolve();
    });
  });

  if ((process.env.DATABASE_URL || "").trim()) {
    try {
      await ensureBackupSchema(defaultDb);
      console.log("[DSK LOCKER Backend] Neon PostgreSQL backup/restore tables verified.");
    } catch (err) {
      console.error(
        "[DSK LOCKER Backend] Initial Neon schema check deferred (will retry via /health):",
        err instanceof Error ? err.message : "Database connection error"
      );
    }
  }
}

if (require.main === module) {
  startServer().catch((err) => {
    console.error("Fatal startup error:", err);
    process.exit(1);
  });
}
