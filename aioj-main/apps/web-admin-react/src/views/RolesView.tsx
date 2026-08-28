import { useQuery } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import { api, type Role } from "@aioj/api-client";
import { Badge, Card, CardBody } from "@aioj/ui-react";
import { ErrorPanel, LoadingPanel, PageHeader, TableShell } from "../components/Common";
import { useI18n } from "../lib/i18n";

const roleMeta: Record<Role, { tone: "green" | "blue" | "red"; descriptionKey: string }> = {
  STUDENT: { tone: "green", descriptionKey: "roles.studentDescription" },
  TEACHER: { tone: "blue", descriptionKey: "roles.teacherDescription" },
  ADMIN: { tone: "red", descriptionKey: "roles.adminDescription" }
};

const CAPABILITY_ROW_KEYS = ["solveSubmit", "useAiChat", "reviewDrafts", "editProblems", "manageUsers", "disableAccounts"] as const;

export function RolesView() {
  const { t } = useI18n();
  const rolesQuery = useQuery({
    queryKey: ["admin-roles"],
    queryFn: () => api.roles()
  });
  const capabilitiesQuery = useQuery({
    queryKey: ["admin-role-capabilities"],
    queryFn: () => api.roleCapabilities()
  });
  const capabilityRows = capabilitiesQuery.data ?? [];
  const matrix = CAPABILITY_ROW_KEYS.map((capabilityKey) => ({
    permission: t(`roles.${capabilityKey}`),
    STUDENT: capabilityRows.some((row) => row.role === "STUDENT" && row.capabilities.includes(capabilityKey)),
    TEACHER: capabilityRows.some((row) => row.role === "TEACHER" && row.capabilities.includes(capabilityKey)),
    ADMIN: capabilityRows.some((row) => row.role === "ADMIN" && row.capabilities.includes(capabilityKey))
  }));
  const roleLabels = new Map((rolesQuery.data ?? []).map((item) => [item.role, item.label]));

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("nav.roles")}
        description={t("roles.matrix")}
      />

      {rolesQuery.isLoading || capabilitiesQuery.isLoading ? (
        <LoadingPanel label={t("common.loading")} />
      ) : rolesQuery.isError || capabilitiesQuery.isError ? (
        <ErrorPanel title={t("roles.loadFailed")} />
      ) : (
        <>
          <section className="grid gap-4 md:grid-cols-3">
            {(["STUDENT", "TEACHER", "ADMIN"] as Role[]).map((role) => (
              <Card key={role} className="rounded-xl shadow-none">
                <CardBody>
                  <div className="flex items-center justify-between gap-3">
                    <Badge tone={roleMeta[role].tone}>{t(`role.${role}`)}</Badge>
                    <ShieldCheck className="size-4 text-[var(--oj-ink-muted)]" aria-hidden="true" />
                  </div>
                  <h2 className="mt-4 text-base font-semibold text-[var(--oj-ink)]">{roleLabels.get(role) || t(`role.${role}`)}</h2>
                  <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{t(roleMeta[role].descriptionKey)}</p>
                </CardBody>
              </Card>
            ))}
          </section>

          <TableShell>
            <table className="w-full min-w-[760px] text-sm">
              <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                <tr>
                  <th className="px-4 py-3 text-left">{t("roles.permission")}</th>
                  {(["STUDENT", "TEACHER", "ADMIN"] as Role[]).map((role) => (
                    <th key={role} className="px-4 py-3 text-center">{t(`role.${role}`)}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--oj-border-soft)]">
                {matrix.map((row) => (
                  <tr key={row.permission}>
                    <td className="px-4 py-3 font-medium text-[var(--oj-ink)]">{row.permission}</td>
                    {(["STUDENT", "TEACHER", "ADMIN"] as Role[]).map((role) => (
                      <td key={role} className="px-4 py-3 text-center">
                        <Badge tone={row[role] ? "green" : "neutral"}>{row[role] ? t("common.yes") : t("common.no")}</Badge>
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </TableShell>
        </>
      )}
    </div>
  );
}
