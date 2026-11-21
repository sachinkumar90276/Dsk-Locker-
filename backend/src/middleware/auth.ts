import { Request, Response, NextFunction } from "express";
import admin from "firebase-admin";
import fs from "fs";
import path from "path";
import { pool } from "../db/pool";

let adminInitialized = false;

export function initFirebaseAdmin() {
  if (adminInitialized || admin.apps.length > 0) {
    adminInitialized = true;
    return;
  }
  const saPath = process.env.FIREBASE_SERVICE_ACCOUNT_PATH;
  if (saPath && fs.existsSync(path.resolve(saPath))) {
    const raw = fs.readFileSync(path.resolve(saPath), "utf8");
    const serviceAccount = JSON.parse(raw);
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
    });
  } else {
    // Fallback to Google Application Default Credentials when hosted on GCP / Cloud Run
    admin.initializeApp();
  }
  adminInitialized = true;
}

export interface AuthenticatedRequest extends Request {
  firebaseUid?: string;
  phoneNumber?: string;
}

/**
 * Verifies the Firebase ID token from Authorization: Bearer <token>.
 * Never trusts client-provided user IDs in body/query/params.
 */
export async function requireFirebaseAuth(
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
    res.status(401).json({ error: "Empty Firebase ID token." });
    return;
  }

  try {
    const decoded = await admin.auth().verifyIdToken(idToken, true);
    const uid = decoded.uid;
    const phone = typeof decoded.phone_number === "string" ? decoded.phone_number : "";

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
  } catch (err) {
    res.status(401).json({
      error: "Firebase ID token verification failed or session expired.",
    });
  }
}

/**
 * Enforces single-owner authorization against the SQL `users` table.
 */
export async function requireAuthorizedOwner(
  req: AuthenticatedRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  const uid = req.firebaseUid;
  if (!uid) {
    res.status(401).json({ error: "Unauthenticated request." });
    return;
  }

  try {
    const existing = await pool.query(
      "SELECT firebase_uid, phone_number, is_authorized_owner FROM users LIMIT 1"
    );

    if (existing.rows.length === 0) {
      res.status(403).json({
        error: "Owner not registered yet. Complete /api/auth/register-owner first.",
      });
      return;
    }

    const owner = existing.rows[0];
    if (owner.firebase_uid !== uid || !owner.is_authorized_owner) {
      res.status(403).json({
        error: "Private single-owner vault: registration & access denied for other accounts.",
      });
      return;
    }

    next();
  } catch (err) {
    res.status(500).json({ error: "Owner verification check failed." });
  }
}
