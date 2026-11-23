import crypto from "crypto";

const PBKDF2_ITERATIONS = 100_000;
const PBKDF2_KEY_LEN = 32;

export function sha256Hex(buf: Buffer): string {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

/**
 * Constant-time PBKDF2-HMAC-SHA256 MPIN verification compatible with Android CryptoManager.hashMpin.
 */
export function verifyPbkdf2Mpin(
  mpin: string,
  saltBase64: string,
  expectedHashBase64: string
): boolean {
  if (!mpin || !saltBase64 || !expectedHashBase64) return false;
  const salt = Buffer.from(saltBase64, "base64");
  const expected = Buffer.from(expectedHashBase64, "base64");
  if (salt.length === 0 || expected.length === 0) return false;
  const derived = crypto.pbkdf2Sync(
    mpin,
    salt,
    PBKDF2_ITERATIONS,
    expected.length,
    "sha256"
  );
  return (
    derived.length === expected.length &&
    crypto.timingSafeEqual(derived, expected)
  );
}

/**
 * Computes lockout duration in seconds on repeated failed MPIN verifications.
 * 5+ failures -> 30s, 7+ -> 60s, 10+ -> 120s.
 */
export function computeMpinLockoutPenaltySeconds(failedAttempts: number): number {
  if (failedAttempts < 5) return 0;
  if (failedAttempts >= 10) return 120;
  if (failedAttempts >= 7) return 60;
  return 30;
}

function deriveMasterWrappingKey(masterSecret: string): Buffer {
  // Derive a 256-bit AES wrapping key cleanly from VAULT_MASTER_SECRET
  return crypto
    .createHash("sha256")
    .update(`dsk_locker_master_wrap_v1:${masterSecret}`)
    .digest();
}

/**
 * Wraps a portable Vault DEK with VAULT_MASTER_SECRET using AES-256-GCM and AAD bound to userId.
 * Fails explicitly if VAULT_MASTER_SECRET is not configured.
 */
export function wrapVaultKeyForUser(
  userId: string,
  plainKeyBase64: string,
  masterSecret = (process.env.VAULT_MASTER_SECRET || "").trim()
): string {
  if (!masterSecret || masterSecret.length < 16) {
    throw new Error(
      "VAULT_MASTER_SECRET environment variable is required (minimum 16 chars) for key recovery."
    );
  }
  const aesKey = deriveMasterWrappingKey(masterSecret);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", aesKey, iv);
  cipher.setAAD(Buffer.from(`uid:${userId}`, "utf8"));
  const encrypted = Buffer.concat([
    cipher.update(Buffer.from(plainKeyBase64, "base64")),
    cipher.final(),
  ]);
  const tag = cipher.getAuthTag();
  return JSON.stringify({
    v: 1,
    alg: "AES-256-GCM",
    iv: iv.toString("base64"),
    tag: tag.toString("base64"),
    ct: encrypted.toString("base64"),
  });
}

/**
 * Unwraps a previously wrapped portable Vault DEK strictly verifying AAD === userId.
 */
export function unwrapVaultKeyForUser(
  userId: string,
  wrappedJson: string,
  masterSecret = (process.env.VAULT_MASTER_SECRET || "").trim()
): string {
  if (!masterSecret || masterSecret.length < 16) {
    throw new Error(
      "VAULT_MASTER_SECRET environment variable is required on server to recover encryption keys."
    );
  }
  const parsed = JSON.parse(wrappedJson) as {
    v: number;
    alg: string;
    iv: string;
    tag: string;
    ct: string;
  };
  if (parsed.alg !== "AES-256-GCM" || !parsed.iv || !parsed.tag || !parsed.ct) {
    throw new Error("Unsupported wrapped vault key format.");
  }
  const aesKey = deriveMasterWrappingKey(masterSecret);
  const decipher = crypto.createDecipheriv(
    "aes-256-gcm",
    aesKey,
    Buffer.from(parsed.iv, "base64")
  );
  decipher.setAAD(Buffer.from(`uid:${userId}`, "utf8"));
  decipher.setAuthTag(Buffer.from(parsed.tag, "base64"));
  const plain = Buffer.concat([
    decipher.update(Buffer.from(parsed.ct, "base64")),
    decipher.final(),
  ]);
  return plain.toString("base64");
}
