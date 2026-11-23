import { Request, Response, NextFunction } from "express";
import admin from "firebase-admin";
import fs from "fs";
import path from "path";
import { DbAdapter, defaultDb } from "../db/pool";

let adminInitialized = false;

function parseServiceAccountJson(raw: string): admin.ServiceAccount {
  const trimmed = raw.trim();
  const jsonText = trimmed.startsWith("{")
    ? trimmed
    : Buffer.from(trimmed, "base64").toString("utf8").trim();
  const parsed = JSON.parse(jsonText) as admin.ServiceAccount & { private_key?: string };
  if (typeof parsed.private_key === "string" && parsed.private_key.includes("\\n")) {
    parsed.private_key = parsed.private_key.replace(/\\n/g, "\n");
  }
  return parsed;
}

export function initFirebaseAdmin() {
  if (adminInitialized || admin.apps.length > 0) {
    adminInitialized = true;
    return;
  }

  const saJson = (process.env.FIREBASE_SERVICE_ACCOUNT_JSON || "").trim();
  const saPath = (process.env.FIREBASE_SERVICE_ACCOUNT_PATH || "").trim();

  if (saJson) {
    try {
      const serviceAccount = parseServiceAccountJson(saJson);
      admin.initializeApp({
        credential: admin.credential.cert(serviceAccount),
      });
    } catch (err) {
      console.error(
        "[DSK LOCKER Backend] Failed to parse FIREBASE_SERVICE_ACCOUNT_JSON:",
        err instanceof Error ? err.message : "Invalid JSON"
      );
      return;
    }
  } else if (saPath && fs.existsSync(path.resolve(saPath))) {
    const raw = fs.readFileSync(path.resolve(saPath), "utf8");
    const serviceAccount = parseServiceAccountJson(raw);
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
    });
  } else {
    console.warn(
      "[DSK LOCKER Backend] FIREBASE_SERVICE_ACCOUNT_JSON not set; falling back to default credentials."
    );
    admin.initializeApp();
  }
  adminInitialized = true;
}

export interface VerifiedTokenIdentity {
  uid: string;
  phoneNumber?: string;
}

export type TokenVerifier = (idToken: string) => Promise<VerifiedTokenIdentity>;

export const defaultFirebaseTokenVerifier: TokenVerifier = async (idToken: string) => {
  initFirebaseAdmin();
  const decoded = await admin.auth().verifyIdToken(idToken, true);
  return {
    uid: decoded.uid,
    phoneNumber: typeof decoded.phone_number === "string" ? decoded.phone_number : "",
  };
};

export interface AuthenticatedRequest extends Request {
  firebaseUid?: string;
  phoneNumber?: string;
}

export function createAuthMiddleware(
  db: DbAdapter = defaultDb,
  verifyToken: TokenVerifier = defaultFirebaseTokenVerifier
) {
  /**
   * Verifies Bearer ID token on every request.
   * Never trusts client-provided user IDs in body/query/params.
   */
  async function requireFirebaseAuth(
    req: AuthenticatedRequest,
    res: Response,
    next: NextFunction
  ): Promise<void> {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
      res.status(401).json({ error: "Missing or invalid Authorization Bearer token." });
      return;
    }

    const idToken = authHeader.slice("Bearer ".length).trim();
    if (!idToken) {
      res.status(401).json({ error: "Empty Bearer authentication token." });
      return;
    }

    try {
      const decoded = await verifyToken(idToken);
      const uid = decoded.uid;
      const phone = (decoded.phoneNumber || "").trim();

      if (!uid) {
        res.status(401).json({ error: "Token did not contain a valid user identity." });
        return;
      }

      const configuredUid = (process.env.OWNER_FIREBASE_UID || "").trim();
      const configuredPhone = (process.env.AUTHORIZED_OWNER_PHONE || "").trim();

      if (configuredUid && configuredUid !== uid) {
        res.status(403).json({ error: "Access denied: unauthorized Firebase UID." });
        return;
      }
      if (configuredPhone && phone && configuredPhone !== phone) {
        res.status(403).json({ error: "Access denied: unauthorized phone number." });
        return;
      }

      req.firebaseUid = uid;
      req.phoneNumber = phone;
      next();
    } catch (_err) {
      res.status(401).json({
        error: "Firebase ID token verification failed or session expired.",
      });
    }
  }

  /**
   * Ensures the authenticated user exists in `users` and respects optional single-owner env pinning.
   * All subsequent queries strictly isolate rows with WHERE user_id = $1 / firebase_uid = $1.
   */
  async function requireAuthorizedOwner(
    req: AuthenticatedRequest,
    res: Response,
    next: NextFunction
  ): Promise<void> {
    const uid = req.firebaseUid;
    if (!uid) {
      res.status(401).json({ error: "Unauthenticated request." });
      return;
    }

    const configuredUid = (process.env.OWNER_FIREBASE_UID || "").trim();
    const configuredPhone = (process.env.AUTHORIZED_OWNER_PHONE || "").trim();
    const hasEnvPin = Boolean(configuredUid || configuredPhone);

    try {
      const userRes = await db.query<{
        firebase_uid: string;
        phone_number: string | null;
        is_authorized_owner: boolean;
      }>(
        "SELECT firebase_uid, phone_number, is_authorized_owner FROM users WHERE firebase_uid = $1",
        [uid]
      );

      if (userRes.rows.length > 0) {
        const userRow = userRes.rows[0];
        if (!userRow.is_authorized_owner) {
          res.status(403).json({ error: "Account disabled or not authorized." });
          return;
        }
        next();
        return;
      }

      if (hasEnvPin) {
        if (configuredUid && configuredUid !== uid) {
          res.status(403).json({ error: "Single-owner vault locked to another Firebase UID." });
          return;
        }
        if (configuredPhone && req.phoneNumber && configuredPhone !== req.phoneNumber) {
          res.status(403).json({ error: "Single-owner vault locked to another phone number." });
          return;
        }
      }

      const now = Date.now();
      await db.query(
        `INSERT INTO users (firebase_uid, phone_number, is_authorized_owner, created_at, last_login_at)
         VALUES ($1, $2, TRUE, $3, $3)
         ON CONFLICT (firebase_uid) DO UPDATE SET last_login_at = EXCLUDED.last_login_at`,
        [uid, req.phoneNumber || null, now]
      );
      await db.query(
        `INSERT INTO user_profiles (firebase_uid, owner_name, phone_number, updated_at)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (firebase_uid) DO NOTHING`,
        [uid, "DSK Owner", req.phoneNumber || "", now]
      );
      await db.query(
        `INSERT INTO backup_settings (user_id, updated_at)
         VALUES ($1, $2)
         ON CONFLICT (user_id) DO NOTHING`,
        [uid, now]
      );

      next();
    } catch (_err) {
      res.status(500).json({ error: "User authorization check failed." });
    }
  }

  return { requireFirebaseAuth, requireAuthorizedOwner };
}

const defaultMiddlewares = createAuthMiddleware();
export const requireFirebaseAuth = defaultMiddlewares.requireFirebaseAuth;
export const requireAuthorizedOwner = defaultMiddlewares.requireAuthorizedOwner;
