import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { api, signOut, type Row } from "../api";
export const useSession = defineStore("session", () => {
  const user = ref<Row | null>(null);
  const integration = ref<Row>({ environment: "未连接", configured: false });
  const admin = computed(() => user.value?.platformRole === "ADMIN");
  const auditor = computed(() => user.value?.platformRole === "AUDITOR");
  const finance = computed(
    () => user.value?.platformRole === "PLATFORM_FINANCE",
  );
  const writable = computed(() => !admin.value && !auditor.value);
  const consumer = computed(() => user.value?.platformRole === "MEMBER" && !user.value?.memberships?.length);
  function can(merchantId: string, roles: string[]) {
    return (
      writable.value &&
      (finance.value ||
        user.value?.memberships?.some(
          (m: Row) => m.merchantId === merchantId && roles.includes(m.roleCode),
        ))
    );
  }
  function canAny(roles: string[]) {
    return writable.value && (finance.value || user.value?.memberships?.some(
      (m: Row) => roles.includes(m.roleCode),
    ));
  }
  async function load() {
    try {
      user.value = await api("/auth/me", { quiet: true });
      return true;
    } catch {
      user.value = null;
      return false;
    }
  }
  async function logout() {
    await signOut();
    user.value = null;
  }
  return {
    user,
    integration,
    admin,
    auditor,
    finance,
    writable,
    consumer,
    can,
    canAny,
    load,
    logout,
  };
});
