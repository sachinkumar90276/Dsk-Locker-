import crypto from "crypto";

export const PBKDF2_ITERATIONS = 100_000;
export const PBKDF2_KEY_BYTES = 32;

export function hashMpinPbkdf2(mpin: string, saltBase64: string): string {
  const salt = Buffer.from(saltBase64, "base64");
  const derived = crypto.pbkdf2Sync(mpin, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BYTES, "sha256");
  return derived.toString("base64");
}

export function verifyMpinPbkdf2(mpin: string, saltBase64: string, expectedHashBase64: string): boolean {
  if (!mpin || !saltBase64 || !expectedHashBase64) return false;
  const actual = Buffer.from(hashMpinPbkdf2(mpin, saltBase64), "base64");
  const expected = Buffer.from(expectedHashBase64, "base64");
  if (actual.length !== expected.length) return false;
  return crypto.timingSafeEqual(actual, expected);
}

export function computeLockoutPenaltySeconds(attempts: number): number {
  if (attempts >= 10) return 120;
  if (attempts >= 7) return 60;
  if (attempts >= 5) return 30;
  return 0;
}
