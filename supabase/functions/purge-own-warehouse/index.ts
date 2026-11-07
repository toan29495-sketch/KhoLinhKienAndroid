import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function listAllFiles(admin: ReturnType<typeof createClient>, bucket: string, folder: string): Promise<string[]> {
  const files: string[] = [];
  let offset = 0;
  while (true) {
    const { data, error } = await admin.storage.from(bucket).list(folder, {
      limit: 100,
      offset,
      sortBy: { column: "name", order: "asc" },
    });
    if (error) throw new Error(`Không thể liệt kê ${bucket}/${folder}: ${error.message}`);
    if (!data || data.length === 0) break;
    for (const entry of data) {
      const path = folder ? `${folder}/${entry.name}` : entry.name;
      if (entry.id === null) {
        files.push(...await listAllFiles(admin, bucket, path));
      } else {
        files.push(path);
      }
    }
    if (data.length < 100) break;
    offset += data.length;
  }
  return files;
}

async function removeOwnerObjects(admin: ReturnType<typeof createClient>, bucket: string, ownerId: string) {
  const paths = await listAllFiles(admin, bucket, ownerId);
  for (let index = 0; index < paths.length; index += 100) {
    const batch = paths.slice(index, index + 100);
    const { error } = await admin.storage.from(bucket).remove(batch);
    if (error) throw new Error(`Không thể xóa ảnh/backup Cloud trong ${bucket}: ${error.message}`);
  }
  return paths.length;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json(405, { success: false, error: "Method not allowed" });

  const match = (req.headers.get("Authorization") ?? "").match(/^Bearer\\s+(.+)$/i);
  if (!match) return json(401, { success: false, error: "Thiếu phiên đăng nhập" });

  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anonKey || !serviceKey) return json(500, { success: false, error: "Thiếu cấu hình Supabase Function" });

  const token = match[1];
  const caller = createClient(url, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { Authorization: `Bearer ${token}` } },
  });
  const admin = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const { data: authData, error: authError } = await caller.auth.getUser(token);
    if (authError || !authData.user) return json(401, { success: false, error: "Phiên đăng nhập không hợp lệ" });

    const { data: profile, error: profileError } = await admin
      .from("profiles").select("id,is_active,must_change_password")
      .eq("id", authData.user.id).maybeSingle();
    if (profileError) throw profileError;
    if (!profile || profile.is_active !== true || profile.must_change_password === true) {
      return json(403, { success: false, error: "Tài khoản không hoạt động hoặc chưa đổi mật khẩu bắt buộc" });
    }

    // First commit the owner-bound database purge. If Storage cleanup later
    // fails, the request can be retried: the SQL RPC is idempotent and the
    // remaining objects are still discoverable under this owner's folders.
    // The RPC uses the caller JWT so auth.uid() remains the exact account whose
    // password was reauthenticated by Android; never use service role here.
    const { data: purgeResult, error: purgeError } = await caller.rpc("purge_my_inventory_data");
    if (purgeError) throw new Error(`Không thể xóa dữ liệu kho trên Cloud: ${purgeError.message}`);

    // Storage API (service role) is used instead of direct DELETE on storage.objects.
    // Both buckets are traversed only below the caller's UUID folder.
    const imageCount = await removeOwnerObjects(admin, "part-images", authData.user.id);
    const backupCount = await removeOwnerObjects(admin, "inventory-backups", authData.user.id);
    return json(200, { success: true, image_objects_deleted: imageCount, backup_objects_deleted: backupCount, purge: purgeResult });
  } catch (error) {
    return json(500, { success: false, error: error instanceof Error ? error.message : "Không thể xóa kho" });
  }
});
