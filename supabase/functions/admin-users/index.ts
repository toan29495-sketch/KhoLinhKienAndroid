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

function usernameToEmail(username: string): string {
  const normalized = username.trim().toLowerCase();
  if (!/^[a-z0-9_-]{3,32}$/.test(normalized)) {
    throw new Error("Tên tài khoản chỉ được gồm 3–32 ký tự chữ thường, số, _ hoặc -");
  }
  const separator = normalized.indexOf("_");
  return separator >= 0
    ? `${normalized.slice(0, separator)}+${normalized.slice(separator + 1)}@gmail.com`
    : `${normalized}@gmail.com`;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json(405, { success: false, error: "Method not allowed" });

  const authorization = req.headers.get("Authorization") ?? "";
  const tokenMatch = authorization.match(/^Bearer\s+(.+)$/i);
  if (!tokenMatch) return json(401, { success: false, error: "Thiếu phiên đăng nhập" });

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    return json(500, { success: false, error: "Thiếu cấu hình Supabase Function secrets" });
  }

  const callerClient = createClient(supabaseUrl, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { Authorization: `Bearer ${tokenMatch[1]}` } },
  });
  const adminClient = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  let auditEventId: string | null = null;
  let auditTargetUserId: string | null = null;

  try {
    const { data: authData, error: authError } = await callerClient.auth.getUser(tokenMatch[1]);
    if (authError || !authData.user) return json(401, { success: false, error: "Phiên đăng nhập không hợp lệ" });

    const { data: callerProfile, error: profileError } = await adminClient
      .from("profiles")
      .select("id, username, role, is_active, must_change_password")
      .eq("id", authData.user.id)
      .maybeSingle();
    if (profileError) throw profileError;
    if (!callerProfile || callerProfile.role !== "admin" || callerProfile.is_active !== true || callerProfile.must_change_password === true) {
      return json(403, { success: false, error: "Chỉ Admin đang hoạt động và đã đổi mật khẩu mới được quản trị tài khoản" });
    }

    const { data: adminLink, error: adminLinkError } = await adminClient
      .from("app_admins")
      .select("user_id")
      .eq("user_id", authData.user.id)
      .maybeSingle();
    if (adminLinkError) throw adminLinkError;
    if (!adminLink) return json(403, { success: false, error: "Tài khoản không có liên kết Admin hợp lệ" });

    const payload: unknown = await req.json();
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      return json(400, { success: false, error: "Dữ liệu yêu cầu không hợp lệ" });
    }
    const input = payload as Record<string, unknown>;
    const action = typeof input.action === "string" ? input.action : "";
    if (!["create", "reset_password", "lock", "unlock"].includes(action)) {
      return json(400, { success: false, error: "Thao tác không được hỗ trợ" });
    }
    const allowedKeys = action === "create"
      ? new Set(["action", "username", "display_name"])
      : new Set(["action", "username"]);
    if (Object.keys(input).some((key) => !allowedKeys.has(key))) {
      return json(400, { success: false, error: "Yêu cầu chứa trường không được phép" });
    }
    if (typeof input.username !== "string") return json(400, { success: false, error: "Tên tài khoản không hợp lệ" });
    const username = input.username.trim().toLowerCase();
    if (!/^[a-z0-9_-]{3,32}$/.test(username)) return json(400, { success: false, error: "Tên tài khoản không hợp lệ" });
    let displayName = "";
    if (action === "create") {
      if (typeof input.display_name !== "string") return json(400, { success: false, error: "Tên hiển thị không hợp lệ" });
      displayName = input.display_name.trim();
      if (!displayName) return json(400, { success: false, error: "Tên hiển thị không được để trống" });
    }

    // Require a durable STARTED audit row before any privileged account mutation.
    const { data: auditEvent, error: auditInsertError } = await adminClient
      .from("admin_audit_events")
      .insert({
        actor_id: authData.user.id,
        actor_username: callerProfile.username,
        target_username: username,
        action,
        status: "STARTED",
      })
      .select("id")
      .single();
    if (auditInsertError || !auditEvent) throw auditInsertError ?? new Error("Không tạo được bản ghi nhật ký quản trị");
    auditEventId = auditEvent.id;

    if (action === "create") {
      const email = usernameToEmail(username);
      const { data: created, error: createError } = await adminClient.auth.admin.createUser({
        email,
        password: "000000",
        email_confirm: true,
        user_metadata: { username, display_name: displayName },
      });
      if (createError) throw createError;
      if (!created.user) throw new Error("Supabase không trả về tài khoản vừa tạo");
      auditTargetUserId = created.user.id;
      const { data: profile, error: profileUpdateError } = await adminClient
        .from("profiles")
        .update({
          username,
          display_name: displayName,
          role: "ktv",
          must_change_password: true,
          is_active: true,
          updated_at: new Date().toISOString(),
        })
        .eq("id", created.user.id)
        .select("id")
        .maybeSingle();
      if (profileUpdateError || !profile) {
        await adminClient.auth.admin.deleteUser(created.user.id);
        auditTargetUserId = null;
        throw profileUpdateError ?? new Error("Trigger không tạo được hồ sơ KTV");
      }
      const { error: auditFinishError } = await adminClient.from("admin_audit_events")
        .update({ target_user_id: auditTargetUserId, status: "SUCCEEDED", finished_at: new Date().toISOString() })
        .eq("id", auditEvent.id);
      return json(200, {
        success: true,
        message: "Đã tạo tài khoản KTV",
        username,
        ...(auditFinishError ? { audit_warning: "AUDIT_FINALIZE_PENDING" } : {}),
      });
    }

    const { data: target, error: targetError } = await adminClient
      .from("profiles")
      .select("id, username, role")
      .eq("username", username)
      .maybeSingle();
    if (targetError) throw targetError;
    if (!target || target.role !== "ktv") {
      await adminClient.from("admin_audit_events")
        .update({ status: "FAILED", error_code: "TARGET_NOT_FOUND", finished_at: new Date().toISOString() })
        .eq("id", auditEvent.id);
      return json(404, { success: false, error: "Không tìm thấy tài khoản KTV" });
    }
    auditTargetUserId = target.id;

    if (action === "reset_password") {
      const { error: updateError } = await adminClient.from("profiles")
        .update({ must_change_password: true, updated_at: new Date().toISOString() })
        .eq("id", target.id);
      if (updateError) throw updateError;

      const { error: guardError } = await adminClient.from("password_reset_guard").upsert({
        user_id: target.id,
        suppress_until: new Date(Date.now() + 2 * 60 * 1000).toISOString(),
      });
      if (guardError) throw guardError;

      const { error: passwordError } = await adminClient.auth.admin.updateUserById(target.id, { password: "000000" });
      if (passwordError) {
        await adminClient.from("password_reset_guard").delete().eq("user_id", target.id);
        throw passwordError;
      }
      const { error: releaseError } = await adminClient.from("password_reset_guard").delete().eq("user_id", target.id);
      if (releaseError) throw releaseError;
    } else if (action === "lock" || action === "unlock") {
      const { error: updateError } = await adminClient.from("profiles")
        .update({ is_active: action === "unlock", updated_at: new Date().toISOString() })
        .eq("id", target.id);
      if (updateError) throw updateError;
    }

    const { error: auditFinishError } = await adminClient.from("admin_audit_events")
      .update({ target_user_id: auditTargetUserId, status: "SUCCEEDED", finished_at: new Date().toISOString() })
      .eq("id", auditEvent.id);
    return json(200, {
      success: true,
      message: "Đã cập nhật tài khoản",
      username,
      ...(auditFinishError ? { audit_warning: "AUDIT_FINALIZE_PENDING" } : {}),
    });
  } catch (error) {
    if (auditEventId) {
      await adminClient.from("admin_audit_events")
        .update({ target_user_id: auditTargetUserId, status: "FAILED", error_code: "ACTION_FAILED", finished_at: new Date().toISOString() })
        .eq("id", auditEventId);
    }
    const message = error instanceof Error ? error.message : "Lỗi không xác định";
    return json(400, { success: false, error: message });
  }
});
