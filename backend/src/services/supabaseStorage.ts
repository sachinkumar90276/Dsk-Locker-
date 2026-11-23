import crypto from "crypto";

export interface SupabaseStorageConfigStatus {
  configured: boolean;
  bucket: string;
  missingVars: string[];
  hasSupabaseUrl: boolean;
  hasServiceRoleKey: boolean;
}

export interface SupabaseUploadResult {
  objectPath: string;
  bucket: string;
  etag: string;
  sha256: string;
  sizeBytes: number;
}

export function getSupabaseConfigStatus(): SupabaseStorageConfigStatus {
  const url = (process.env.SUPABASE_URL || "").trim();
  const key = (process.env.SUPABASE_SERVICE_ROLE_KEY || "").trim();
  const bucket = (process.env.SUPABASE_STORAGE_BUCKET || "dsk-locker-vault").trim() || "dsk-locker-vault";
  const missingVars: string[] = [];

  if (!url || url.includes("your-project-ref.supabase.co") || !url.startsWith("http")) {
    missingVars.push("SUPABASE_URL");
  }
  if (!key || key === "your-supabase-service-role-key") {
    missingVars.push("SUPABASE_SERVICE_ROLE_KEY");
  }

  return {
    configured: missingVars.length === 0,
    bucket,
    missingVars,
    hasSupabaseUrl: !missingVars.includes("SUPABASE_URL"),
    hasServiceRoleKey: !missingVars.includes("SUPABASE_SERVICE_ROLE_KEY"),
  };
}

export function sanitizeSegment(raw: string, fallback = "item"): string {
  const cleaned = raw.replace(/[^a-zA-Z0-9._-]/g, "_").replace(/^\.+/, "");
  return cleaned.length > 0 ? cleaned.slice(0, 120) : fallback;
}

export function folderForCategory(categoryId: string, isInVault: boolean): string {
  if (isInVault) return "vault";
  switch (categoryId) {
    case "photos":
      return "photos";
    case "videos":
      return "videos";
    case "audio":
      return "audio";
    default:
      return "documents";
  }
}

export function buildUserScopedObjectPath(
  firebaseUid: string,
  fileId: string,
  categoryId: string,
  isInVault: boolean,
  originalFilename: string
): string {
  const safeUid = sanitizeSegment(firebaseUid, "");
  const safeFileId = sanitizeSegment(fileId, "");
  if (!safeUid || !safeFileId) {
    throw new Error("Invalid firebaseUid or fileId for storage path.");
  }
  const safeName = sanitizeSegment(originalFilename, "document");
  const folder = folderForCategory(categoryId, isInVault);
  const encSuffix = safeName.endsWith(".enc") ? "" : ".enc";
  return `users/${safeUid}/${folder}/${safeFileId}_${safeName}${encSuffix}`;
}

export function assertPathBelongsToUser(objectPath: string, firebaseUid: string): void {
  const safeUid = sanitizeSegment(firebaseUid, "");
  if (!safeUid) throw new Error("Invalid user scope.");
  if (
    objectPath.includes("..") ||
    objectPath.startsWith("/") ||
    !objectPath.startsWith(`users/${safeUid}/`)
  ) {
    throw new Error("Access denied: storage path violates user isolation boundary.");
  }
}

function encodeObjectPath(objectPath: string): string {
  return objectPath
    .split("/")
    .filter((s) => s.length > 0)
    .map((s) => encodeURIComponent(s))
    .join("/");
}

async function ensureBucketExists(
  baseUrl: string,
  serviceKey: string,
  bucket: string
): Promise<void> {
  const createResp = await fetch(`${baseUrl}/storage/v1/bucket`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${serviceKey}`,
      apikey: serviceKey,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      id: bucket,
      name: bucket,
      public: false,
    }),
  });
  if (!createResp.ok && createResp.status !== 409) {
    const body = await createResp.text().catch(() => "");
    throw new Error(`Supabase bucket creation failed (${createResp.status}): ${body}`);
  }
}

export async function uploadEncryptedObject(
  objectPath: string,
  firebaseUid: string,
  bytes: Buffer,
  contentType: string
): Promise<SupabaseUploadResult> {
  assertPathBelongsToUser(objectPath, firebaseUid);
  const cfg = getSupabaseConfigStatus();
  if (!cfg.configured) {
    throw new Error(
      `Supabase Storage is not configured. Missing environment variable(s): ${cfg.missingVars.join(", ")}`
    );
  }

  const baseUrl = process.env.SUPABASE_URL!.trim().replace(/\/+$/, "");
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY!.trim();
  const encodedPath = encodeObjectPath(objectPath);
  const targetUrl = `${baseUrl}/storage/v1/object/${encodeURIComponent(cfg.bucket)}/${encodedPath}`;
  const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");

  const doPut = () =>
    fetch(targetUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${serviceKey}`,
        apikey: serviceKey,
        "Content-Type": contentType || "application/octet-stream",
        "x-upsert": "true",
      },
      body: new Uint8Array(bytes),
    });

  let resp = await doPut();
  if (resp.status === 404 || resp.status === 400) {
    const errText = await resp.text().catch(() => "");
    if (errText.toLowerCase().includes("bucket") && errText.toLowerCase().includes("not found")) {
      await ensureBucketExists(baseUrl, serviceKey, cfg.bucket);
      resp = await doPut();
    } else {
      throw new Error(`Supabase Storage upload failed (${resp.status}): ${errText}`);
    }
  }

  if (!resp.ok) {
    const errText = await resp.text().catch(() => "");
    throw new Error(`Supabase Storage upload failed (${resp.status}): ${errText}`);
  }

  const etag = resp.headers.get("etag")?.replace(/"/g, "") || sha256.slice(0, 16);
  return {
    objectPath,
    bucket: cfg.bucket,
    etag,
    sha256,
    sizeBytes: bytes.length,
  };
}

export async function downloadEncryptedObject(
  objectPath: string,
  firebaseUid: string,
  bucketOverride?: string | null
): Promise<Buffer> {
  assertPathBelongsToUser(objectPath, firebaseUid);
  const cfg = getSupabaseConfigStatus();
  if (!cfg.configured) {
    throw new Error(
      `Supabase Storage is not configured. Missing environment variable(s): ${cfg.missingVars.join(", ")}`
    );
  }

  const baseUrl = process.env.SUPABASE_URL!.trim().replace(/\/+$/, "");
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY!.trim();
  const bucket = (bucketOverride && bucketOverride.trim()) || cfg.bucket;
  const encodedPath = encodeObjectPath(objectPath);
  const targetUrl = `${baseUrl}/storage/v1/object/${encodeURIComponent(bucket)}/${encodedPath}`;

  const resp = await fetch(targetUrl, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${serviceKey}`,
      apikey: serviceKey,
    },
  });

  if (!resp.ok) {
    const errText = await resp.text().catch(() => "");
    throw new Error(`Supabase Storage download failed (${resp.status}): ${errText}`);
  }

  const arr = await resp.arrayBuffer();
  return Buffer.from(arr);
}

export async function deleteEncryptedObject(
  objectPath: string,
  firebaseUid: string,
  bucketOverride?: string | null
): Promise<void> {
  assertPathBelongsToUser(objectPath, firebaseUid);
  const cfg = getSupabaseConfigStatus();
  if (!cfg.configured) {
    throw new Error(
      `Supabase Storage is not configured. Missing environment variable(s): ${cfg.missingVars.join(", ")}`
    );
  }

  const baseUrl = process.env.SUPABASE_URL!.trim().replace(/\/+$/, "");
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY!.trim();
  const bucket = (bucketOverride && bucketOverride.trim()) || cfg.bucket;
  const encodedPath = encodeObjectPath(objectPath);
  const targetUrl = `${baseUrl}/storage/v1/object/${encodeURIComponent(bucket)}/${encodedPath}`;

  const resp = await fetch(targetUrl, {
    method: "DELETE",
    headers: {
      Authorization: `Bearer ${serviceKey}`,
      apikey: serviceKey,
    },
  });

  if (!resp.ok && resp.status !== 404) {
    const errText = await resp.text().catch(() => "");
    throw new Error(`Supabase Storage delete failed (${resp.status}): ${errText}`);
  }
}
