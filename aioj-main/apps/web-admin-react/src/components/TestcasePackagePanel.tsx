import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import JSZip from "jszip";
import { api, ApiError, type AppendTestcasePackageCasesPayload, type EntityId, type TestcasePackageResponse, type TestcaseUploadStatusResponse } from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { ConfirmDialog, ErrorPanel, LoadingPanel, SidePanel, inputClass, selectClass } from "./Common";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { formatBytes, packageStatusTone } from "../lib/format";
import { readableCaughtError, readableStoredError } from "../lib/readableError";

const CHUNK_SIZE_BYTES = 4 * 1024 * 1024;
const MAX_CASE_FILE_BYTES = 8 * 1024 * 1024;
const MAX_PACKAGE_BYTES = 50 * 1024 * 1024;
const MAX_MANIFEST_BYTES = 1 * 1024 * 1024;
const MANIFEST_PATH = "manifest.json";

type UploadPhase = "idle" | "scanning" | "building" | "hashing" | "uploading" | "completing" | "polling" | "ready" | "failed";

interface ZipEntryInfo {
  path: string;
  sizeBytes: number;
  isDir: boolean;
}

interface ManifestDraftCase {
  id: string;
  name: string;
  input: string;
  output: string;
  sample: boolean;
  subtaskKey: string;
  score: number | null;
}

interface ManifestDraftSubtask {
  id: string;
  key: string;
  title: string;
  score: number | null;
  sortOrder: number;
}

interface ManifestDraftChecker {
  type: "STANDARD" | "CUSTOM";
  language: "cpp";
  source: string;
  protocol: "AIOJ_JSON";
}

interface ManifestDraft {
  version: string;
  checker: ManifestDraftChecker;
  subtasks: ManifestDraftSubtask[];
  cases: ManifestDraftCase[];
}

export function TestcasePackagePanel({ problemId }: { problemId: EntityId | null }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const fileInput = React.useRef<HTMLInputElement | null>(null);
  const [file, setFile] = React.useState<File | null>(null);
  const [uploadSizeBytes, setUploadSizeBytes] = React.useState(0);
  const [fileSha256, setFileSha256] = React.useState("");
  const [status, setStatus] = React.useState<TestcaseUploadStatusResponse | null>(null);
  const [phase, setPhase] = React.useState<UploadPhase>("idle");
  const [error, setError] = React.useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = React.useState<TestcasePackageResponse | null>(null);
  const [appendTarget, setAppendTarget] = React.useState<TestcasePackageResponse | null>(null);
  const [downloadingId, setDownloadingId] = React.useState<EntityId | null>(null);
  const [zipEntries, setZipEntries] = React.useState<ZipEntryInfo[]>([]);
  const [hasExistingManifest, setHasExistingManifest] = React.useState(false);
  const [manifestDraft, setManifestDraft] = React.useState<ManifestDraft>(() => emptyManifestDraft());

  const packagesQuery = useQuery({
    queryKey: ["testcase-packages", problemId],
    queryFn: () => api.testcasePackages(problemId!),
    enabled: Boolean(problemId)
  });

  const refreshPackageState = React.useCallback(async () => {
    if (!problemId) return;
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["testcase-packages", problemId] }),
      queryClient.invalidateQueries({ queryKey: ["admin-problems"] }),
      queryClient.invalidateQueries({ queryKey: ["problem", problemId] })
    ]);
  }, [problemId, queryClient]);

  const activateMutation = useMutation({
    mutationFn: (packageId: EntityId) => api.activateTestcasePackage(problemId!, packageId),
    onSuccess: async () => {
      toast.success(t("testcase.activatedMessage"));
      await refreshPackageState();
    },
    onError: (caught) => {
      toast.error(readableUploadError(caught, t("testcase.actionFailed"), t, locale));
    }
  });

  const archiveMutation = useMutation({
    mutationFn: (packageId: EntityId) => api.archiveTestcasePackage(problemId!, packageId),
    onSuccess: async () => {
      toast.success(t("testcase.archivedMessage"));
      await refreshPackageState();
    },
    onError: (caught) => {
      toast.error(readableUploadError(caught, t("testcase.actionFailed"), t, locale));
    }
  });

  const restoreMutation = useMutation({
    mutationFn: (packageId: EntityId) => api.restoreTestcasePackage(problemId!, packageId),
    onSuccess: async () => {
      toast.success(t("testcase.restoredMessage"));
      await refreshPackageState();
    },
    onError: (caught) => {
      toast.error(readableUploadError(caught, t("testcase.actionFailed"), t, locale));
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (packageId: EntityId) => api.deleteTestcasePackage(problemId!, packageId),
    onSuccess: async () => {
      toast.success(t("testcase.deletedMessage"));
      await refreshPackageState();
    },
    onError: (caught) => {
      toast.error(readableUploadError(caught, t("testcase.actionFailed"), t, locale));
    }
  });

  const appendCaseMutation = useMutation({
    mutationFn: (payload: { packageId: EntityId; form: AppendTestcasePackageCasesPayload }) =>
      api.appendTestcasePackageCases(problemId!, payload.packageId, payload.form),
    onSuccess: async () => {
      setAppendTarget(null);
      toast.success(t("testcase.appendedMessage"));
      await refreshPackageState();
    },
    onError: (caught) => {
      toast.error(readableUploadError(caught, t("testcase.appendCaseFailed"), t, locale));
    }
  });

  const orderedPackages = React.useMemo(() => orderPackagesByUploadTime(packagesQuery.data ?? []), [packagesQuery.data]);
  const activePackage = orderedPackages.find((item) => item.active) ?? null;
  const progress = status ? (status.progress <= 1 ? status.progress * 100 : status.progress) : 0;
  const filePaths = React.useMemo(() => sortedFilePaths(zipEntries), [zipEntries]);
  const busy = phase === "scanning" || phase === "building" || phase === "hashing" || phase === "uploading" || phase === "completing" || phase === "polling";
  const activatingPackageId = activateMutation.isPending ? activateMutation.variables : undefined;
  const busyPackageId = archiveMutation.isPending
    ? archiveMutation.variables
    : restoreMutation.isPending
      ? restoreMutation.variables
      : deleteMutation.isPending
        ? deleteMutation.variables
        : undefined;
  const appendingPackageId = appendCaseMutation.isPending ? appendCaseMutation.variables?.packageId : undefined;

  function selectFile(event: React.ChangeEvent<HTMLInputElement>) {
    const nextFile = event.target.files?.[0] ?? null;
    void prepareSelectedFile(nextFile);
  }

  async function prepareSelectedFile(nextFile: File | null) {
    setError(null);
    setStatus(null);
    setFileSha256("");
    setZipEntries([]);
    setHasExistingManifest(false);
    setManifestDraft(emptyManifestDraft());
    setUploadSizeBytes(0);
    setPhase("idle");
    if (!nextFile) {
      setFile(null);
      return;
    }
    if (!nextFile.name.toLowerCase().endsWith(".zip")) {
      setError(t("testcase.onlyZip"));
      setFile(null);
      if (fileInput.current) fileInput.current.value = "";
      return;
    }
    if (nextFile.size > MAX_PACKAGE_BYTES) {
      setError(t("testcase.fileTooLarge"));
      setFile(null);
      if (fileInput.current) fileInput.current.value = "";
      return;
    }
    try {
      setPhase("scanning");
      const scan = await analyzeZip(nextFile, t);
      setFile(nextFile);
      setUploadSizeBytes(nextFile.size);
      setZipEntries(scan.entries);
      setHasExistingManifest(scan.hasExistingManifest);
      setManifestDraft(scan.hasExistingManifest ? emptyManifestDraft() : inferDraft(scan.entries));
      setPhase("idle");
    } catch (caught) {
      setFile(null);
      setZipEntries([]);
      setHasExistingManifest(false);
      setManifestDraft(emptyManifestDraft());
      setPhase("failed");
      setError(readableUploadError(caught, t("testcase.zipReadFailed"), t, locale));
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  async function upload() {
    if (!problemId) return setError(t("testcase.noProblem"));
    if (!file) return setError(t("testcase.noFile"));
    setError(null);
    setStatus(null);
    let activeUploadId: string | null = null;
    try {
      let uploadBlob: Blob = file;
      if (!hasExistingManifest) {
        const validationError = validateManifestDraft(manifestDraft, filePaths, t);
        if (validationError) {
          setPhase("failed");
          setError(validationError);
          return;
        }
        setPhase("building");
        uploadBlob = await injectManifest(file, manifestDraft);
        if (uploadBlob.size > MAX_PACKAGE_BYTES) {
          throw new Error(t("testcase.fileTooLarge"));
        }
        setUploadSizeBytes(uploadBlob.size);
      }

      setPhase("hashing");
      const totalSha = await sha256(uploadBlob);
      setFileSha256(totalSha);
      const totalChunks = Math.ceil(uploadBlob.size / CHUNK_SIZE_BYTES);
      const init = await api.initTestcasePackage(problemId, {
        fileName: file.name,
        fileSizeBytes: uploadBlob.size,
        sha256: totalSha,
        chunkSizeBytes: CHUNK_SIZE_BYTES,
        totalChunks
      });
      activeUploadId = init.uploadId;
      let uploadStatus: TestcaseUploadStatusResponse = {
        uploadId: init.uploadId,
        status: init.status,
        uploadedChunks: init.uploadedChunks,
        totalChunks: init.totalChunks,
        progress: init.totalChunks ? init.uploadedChunks.length / init.totalChunks : 0,
        packageId: init.packageId
      };
      setStatus(uploadStatus);
      setPhase("uploading");
      const uploaded = new Set(init.uploadedChunks);
      for (let index = 0; index < init.totalChunks; index += 1) {
        if (uploaded.has(index)) continue;
        const chunk = uploadBlob.slice(index * CHUNK_SIZE_BYTES, Math.min(uploadBlob.size, (index + 1) * CHUNK_SIZE_BYTES));
        uploadStatus = await api.uploadTestcaseChunk(problemId, init.uploadId, index, chunk, await sha256(chunk));
        setStatus(uploadStatus);
      }
      setPhase("completing");
      const completed = await api.completeTestcaseUpload(problemId, init.uploadId);
      if (completed.status === "READY") {
        setPhase("ready");
      } else if (completed.status === "FAILED") {
        throw new Error(readableStoredError(completed.errorMessage, locale, t("testcase.uploadFailed"), "testcase"));
      } else {
        setPhase("polling");
        await pollUntilReady(problemId, init.uploadId);
        setPhase("ready");
      }
      setFile(null);
      setZipEntries([]);
      setHasExistingManifest(false);
      setManifestDraft(emptyManifestDraft());
      if (fileInput.current) fileInput.current.value = "";
      setUploadSizeBytes(0);
      await refreshPackageState();
    } catch (caught) {
      setPhase("failed");
      setError(readableUploadError(caught, t("testcase.initFailed"), t, locale));
      if (activeUploadId) {
        await failUploadSession(problemId, activeUploadId);
        await refreshPackageState().catch(() => undefined);
      }
    }
  }

  async function downloadPackage(item: TestcasePackageResponse) {
    if (!problemId) return;
    setDownloadingId(item.id);
    try {
      const fileResponse = await api.downloadTestcasePackage(problemId, item.id);
      saveDownloadedFile(fileResponse.blob, testcaseDownloadFileName(fileResponse.fileName, item.fileName));
    } catch (caught) {
      toast.error(readableUploadError(caught, t("testcase.downloadFailed"), t, locale));
    } finally {
      setDownloadingId(null);
    }
  }

  if (!problemId) {
    return (
      <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4 text-sm text-[var(--oj-ink-muted)]">
        {t("testcase.noProblem")}
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("testcase.title")}</h3>
          <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("testcase.subtitle")}</p>
        </div>
        <Button variant="outline" onClick={() => void packagesQuery.refetch()}>{t("common.refresh")}</Button>
      </div>
      {error ? <ErrorPanel title={error} /> : null}
      <div className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <div className="flex flex-wrap items-center gap-3">
          <input ref={fileInput} type="file" accept=".zip,application/zip,application/x-zip-compressed" onChange={selectFile} />
          <Button disabled={!file || busy} onClick={() => void upload()}>
            {busy ? t("common.loading") : t("testcase.upload")}
          </Button>
        </div>
        {file ? (
          <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-4">
            <Fact label={t("testcase.selectedFile")} value={file.name} />
            <Fact label={t("testcase.fileSize")} value={formatBytes(uploadSizeBytes || file.size)} />
            <Fact label={t("testcase.chunkSize")} value={formatBytes(CHUNK_SIZE_BYTES)} />
            <Fact label={t("testcase.sha256")} value={fileSha256 || phaseLabel(phase, t)} />
          </dl>
        ) : null}
        {file && hasExistingManifest ? (
          <div className="mt-4 rounded-xl border border-blue-100 bg-blue-50 px-4 py-3 text-sm leading-6 text-blue-950">
            {t("testcase.manifestAlreadyExists")}
          </div>
        ) : null}
        {file && !hasExistingManifest ? (
          <TestcaseManifestEditor
            draft={manifestDraft}
            filePaths={filePaths}
            onChange={setManifestDraft}
            onRemoveCase={(index) => setManifestDraft((current) => removeManifestCase(current, index))}
          />
        ) : null}
        {status ? (
          <div className="mt-4">
            <div className="flex items-center justify-between text-sm">
              <span className="text-[var(--oj-ink-muted)]">{phaseLabel(phase, t)}</span>
              <strong className="tabular-nums">{Math.round(progress)}%</strong>
            </div>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-[var(--oj-border-soft)]">
              <div className="h-full rounded-full bg-[var(--oj-primary)]" style={{ width: `${progress}%` }} />
            </div>
          </div>
        ) : null}
      </div>

      {packagesQuery.isLoading ? (
        <LoadingPanel label={t("common.loading")} />
      ) : packagesQuery.isError ? (
        <ErrorPanel title={t("testcase.listFailed")} />
      ) : (
        <div className="space-y-4">
          <PackageSummary
            title={t("testcase.activePackage")}
            packages={activePackage ? [activePackage] : []}
            onDownload={(item) => void downloadPackage(item)}
            onAppendCase={setAppendTarget}
            appendingId={appendingPackageId}
            downloadingId={downloadingId ?? undefined}
          />
          <PackageSummary
            title={t("testcase.packages")}
            packages={orderedPackages}
            onActivate={(item) => void activateMutation.mutate(item.id)}
            onArchive={(item) => void archiveMutation.mutate(item.id)}
            onRestore={(item) => void restoreMutation.mutate(item.id)}
            onDelete={setDeleteTarget}
            onDownload={(item) => void downloadPackage(item)}
            onAppendCase={setAppendTarget}
            activatingId={activatingPackageId}
            busyId={busyPackageId}
            appendingId={appendingPackageId}
            downloadingId={downloadingId ?? undefined}
          />
        </div>
      )}
      <AppendCasesDialog
        target={appendTarget}
        open={Boolean(appendTarget)}
        saving={appendCaseMutation.isPending}
        onOpenChange={(open) => {
          if (!open && !appendCaseMutation.isPending) {
            setAppendTarget(null);
          }
        }}
        onSubmit={(form) => {
          if (!appendTarget) return;
          appendCaseMutation.mutate({ packageId: appendTarget.id, form });
        }}
      />
      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("testcase.deleteTitle")}
        description={deleteTarget ? `${deleteTarget.version}\n${t("testcase.deleteConfirm")}` : ""}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("common.delete")}
        onConfirm={async () => {
          const target = deleteTarget;
          if (!target) return;
          setDeleteTarget(null);
          deleteMutation.mutate(target.id);
        }}
      />
    </div>
  );
}

function orderPackagesByUploadTime(packages: TestcasePackageResponse[]) {
  return [...packages].sort((left, right) => {
    const leftTime = Date.parse(left.createdAt);
    const rightTime = Date.parse(right.createdAt);
    const timeDiff = (Number.isNaN(leftTime) ? 0 : leftTime) - (Number.isNaN(rightTime) ? 0 : rightTime);
    return timeDiff || left.id.localeCompare(right.id);
  });
}

function saveDownloadedFile(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName || "testcase-package.zip";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function testcaseDownloadFileName(responseName: string, packageName: string) {
  const name = responseName && responseName !== "download.bin" ? responseName : packageName;
  return normalizeZipFileName(name || "testcase-package.zip");
}

function normalizeZipFileName(fileName: string) {
  const normalized = fileName.trim().replace(/[\r\n/\\]/g, "_") || "testcase-package.zip";
  return normalized.toLowerCase().endsWith(".zip") ? normalized : `${normalized}.zip`;
}

interface AppendCaseDraftRow {
  id: string;
  caseName: string;
  score: string;
  subtaskKey: string;
  inputFile: File | null;
  outputFile: File | null;
}

function AppendCasesDialog({
  target,
  open,
  saving,
  onOpenChange,
  onSubmit
}: {
  target: TestcasePackageResponse | null;
  open: boolean;
  saving: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (form: AppendTestcasePackageCasesPayload) => void;
}) {
  const { t } = useI18n();
  const [rows, setRows] = React.useState<AppendCaseDraftRow[]>([]);
  const [localError, setLocalError] = React.useState<string | null>(null);
  const subtasks = target?.subtasks ?? [];

  React.useEffect(() => {
    if (!target) {
      setRows([]);
      return;
    }
    setRows([createAppendCaseRow(target, 0)]);
    setLocalError(null);
  }, [target]);

  function updateRow(rowId: string, patch: Partial<AppendCaseDraftRow>) {
    setRows((current) => current.map((row) => row.id === rowId ? { ...row, ...patch } : row));
  }

  function addRow() {
    if (!target) {
      return;
    }
    setRows((current) => [...current, createAppendCaseRow(target, current.length)]);
  }

  function removeRow(rowId: string) {
    setRows((current) => current.filter((row) => row.id !== rowId));
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving || !target) {
      return;
    }
    if (!rows.length) {
      setLocalError(t("testcase.appendCaseNoRows"));
      return;
    }
    const seenNames = new Set<string>();
    const normalizedCases = [];
    for (let index = 0; index < rows.length; index += 1) {
      const row = rows[index];
      const rowLabel = t("testcase.appendCaseRowLabel", { index: index + 1 });
      const normalizedName = row.caseName.trim();
      if (!normalizedName) {
        setLocalError(`${rowLabel}: ${t("testcase.appendCaseMissingName")}`);
        return;
      }
      const nameKey = normalizedName.toLowerCase();
      if (seenNames.has(nameKey)) {
        setLocalError(`${rowLabel}: ${t("testcase.appendCaseDuplicateName")}`);
        return;
      }
      seenNames.add(nameKey);
      if (!row.inputFile || !row.outputFile) {
        setLocalError(`${rowLabel}: ${t("testcase.appendCaseMissingFiles")}`);
        return;
      }
      if (row.inputFile.size > MAX_CASE_FILE_BYTES || row.outputFile.size > MAX_CASE_FILE_BYTES) {
        setLocalError(`${rowLabel}: ${t("testcase.appendCaseFileTooLarge", { limit: formatBytes(MAX_CASE_FILE_BYTES) })}`);
        return;
      }
      const normalizedScore = Number(row.score);
      if (!Number.isFinite(normalizedScore) || normalizedScore < 0 || !Number.isInteger(normalizedScore)) {
        setLocalError(`${rowLabel}: ${t("testcase.appendCaseInvalidScore")}`);
        return;
      }
      if (subtasks.length > 0 && !row.subtaskKey) {
        setLocalError(`${rowLabel}: ${t("testcase.appendCaseMissingSubtask")}`);
        return;
      }
      normalizedCases.push({
        caseName: normalizedName,
        score: normalizedScore,
        subtaskKey: subtasks.length > 0 ? row.subtaskKey : null,
        inputFile: row.inputFile,
        outputFile: row.outputFile
      });
    }
    setLocalError(null);
    onSubmit({ cases: normalizedCases });
  }

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      title={t("testcase.appendCaseTitle")}
      description={target ? `${target.version} · ${t("testcase.appendCaseDescription")}` : t("testcase.appendCaseDescription")}
      presentation="workspace"
      workspaceSize="lg"
      workspaceHeight="fit"
      footer={(
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" disabled={saving} onClick={() => onOpenChange(false)}>
            {t("common.cancel")}
          </Button>
          <Button type="submit" form="append-testcase-case-form" disabled={saving}>
            {saving ? t("common.loading") : t("testcase.appendCaseSubmit")}
          </Button>
        </div>
      )}
    >
      <form id="append-testcase-case-form" className="space-y-4" onSubmit={submit}>
        {target ? (
          <div className="rounded-xl border border-blue-100 bg-blue-50 px-4 py-3 text-sm leading-6 text-blue-950">
            {target.active ? t("testcase.appendCaseActiveHint") : t("testcase.appendCaseInactiveHint")}
          </div>
        ) : null}
        {localError ? (
          <div className="rounded-xl border border-orange-200 bg-orange-50 px-4 py-3 text-sm text-orange-900">
            {localError}
          </div>
        ) : null}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-[var(--oj-ink-muted)]">
            {t("testcase.appendCaseBatchCount", { count: rows.length })}
          </p>
          <Button type="button" size="sm" variant="outline" disabled={saving} onClick={addRow}>
            {t("testcase.appendCaseAddRow")}
          </Button>
        </div>

        <div className="space-y-3">
          {rows.map((row, index) => (
            <article key={row.id} className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
              <div className="mb-3 flex items-center justify-between gap-3">
                <strong className="text-sm text-[var(--oj-ink)]">
                  {t("testcase.appendCaseRowLabel", { index: index + 1 })}
                </strong>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  className="text-red-700 hover:text-red-800"
                  disabled={saving || rows.length <= 1}
                  onClick={() => removeRow(row.id)}
                >
                  {t("testcase.appendCaseRemoveRow")}
                </Button>
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <CompactField label={t("testcase.appendCaseNameLabel")}>
                  <input
                    className={inputClass}
                    value={row.caseName}
                    maxLength={160}
                    disabled={saving}
                    placeholder={t("testcase.appendCaseNamePlaceholder")}
                    onChange={(event) => updateRow(row.id, { caseName: event.target.value })}
                  />
                </CompactField>
                <CompactField label={t("testcase.appendCaseScoreLabel")}>
                  <input
                    className={inputClass}
                    type="number"
                    min={0}
                    step={1}
                    value={row.score}
                    disabled={saving}
                    onChange={(event) => updateRow(row.id, { score: event.target.value })}
                  />
                </CompactField>
                {subtasks.length > 0 ? (
                  <CompactField label={t("testcase.appendCaseSubtaskLabel")}>
                    <select
                      className={selectClass}
                      value={row.subtaskKey}
                      disabled={saving}
                      onChange={(event) => updateRow(row.id, { subtaskKey: event.target.value })}
                    >
                      <option value="">{t("testcase.appendCaseSubtaskPlaceholder")}</option>
                      {subtasks.map((item) => (
                        <option key={item.key} value={item.key}>
                          {item.key}{item.title ? ` · ${item.title}` : ""}
                        </option>
                      ))}
                    </select>
                  </CompactField>
                ) : null}
                <CompactField label={t("testcase.appendCaseInputFileLabel")}>
                  <input
                    className={inputClass}
                    type="file"
                    accept=".in"
                    disabled={saving}
                    onChange={(event) => updateRow(row.id, { inputFile: event.target.files?.[0] ?? null })}
                  />
                </CompactField>
                <CompactField label={t("testcase.appendCaseOutputFileLabel")}>
                  <input
                    className={inputClass}
                    type="file"
                    accept=".out"
                    disabled={saving}
                    onChange={(event) => updateRow(row.id, { outputFile: event.target.files?.[0] ?? null })}
                  />
                </CompactField>
              </div>
            </article>
          ))}
        </div>
        <p className="text-sm leading-6 text-[var(--oj-ink-muted)]">
          {t("testcase.appendCaseFileHint", {
            caseLimit: formatBytes(MAX_CASE_FILE_BYTES),
            packageLimit: formatBytes(MAX_PACKAGE_BYTES)
          })}
        </p>
      </form>
    </SidePanel>
  );
}

function createAppendCaseRow(target: TestcasePackageResponse, index: number): AppendCaseDraftRow {
  return {
    id: `append-${Date.now()}-${Math.random().toString(36).slice(2)}-${index}`,
    caseName: `case-${(target.caseCount ?? 0) + index + 1}`,
    score: "1",
    subtaskKey: target.subtasks?.[0]?.key ?? "",
    inputFile: null,
    outputFile: null
  };
}

function PackageSummary({
  title,
  packages,
  onActivate,
  onArchive,
  onRestore,
  onDelete,
  onDownload,
  onAppendCase,
  activatingId,
  busyId,
  appendingId,
  downloadingId
}: {
  title: string;
  packages: TestcasePackageResponse[];
  onActivate?: (item: TestcasePackageResponse) => void;
  onArchive?: (item: TestcasePackageResponse) => void;
  onRestore?: (item: TestcasePackageResponse) => void;
  onDelete?: (item: TestcasePackageResponse) => void;
  onDownload?: (item: TestcasePackageResponse) => void;
  onAppendCase?: (item: TestcasePackageResponse) => void;
  activatingId?: EntityId;
  busyId?: EntityId;
  appendingId?: EntityId;
  downloadingId?: EntityId;
}) {
  const { t, locale } = useI18n();
  return (
    <section>
      <h3 className="mb-2 text-sm font-semibold text-[var(--oj-ink)]">{title}</h3>
      {packages.length ? (
        <div className="space-y-2">
          {packages.map((item) => (
            <article key={item.id} className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border-soft)] bg-white p-3 md:flex-row md:items-center md:justify-between">
              <div className="min-w-0">
                <strong className="block truncate text-sm text-[var(--oj-ink)]">{item.version}</strong>
                <span className="block truncate text-xs text-[var(--oj-ink-muted)]">{item.fileName} · {formatBytes(item.fileSizeBytes)}</span>
                <span className="block text-xs text-[var(--oj-ink-muted)]">{t("testcase.caseCount", { count: item.caseCount })}</span>
                {item.errorMessage ? <span className="mt-1 block text-xs text-red-700">{readableStoredError(item.errorMessage, locale, item.errorMessage, "testcase")}</span> : null}
              </div>
              <div className="flex shrink-0 flex-wrap items-center gap-2">
                <Badge tone={packageStatusTone(item.status)}>{t(`packageStatus.${item.status}`)}</Badge>
                {item.active ? <Badge tone="green">{t("common.active")}</Badge> : null}
                {item.archivedAt ? <Badge tone="neutral">{t("common.archived")}</Badge> : null}
                {onAppendCase && item.status === "READY" && !item.archivedAt ? (
                  <Button size="sm" variant="outline" disabled={appendingId === item.id} onClick={() => onAppendCase(item)}>
                    {appendingId === item.id ? t("common.loading") : t("testcase.appendCase")}
                  </Button>
                ) : null}
                {onDownload && item.status === "READY" ? (
                  <Button size="sm" variant="outline" disabled={downloadingId === item.id} onClick={() => onDownload(item)}>
                    {downloadingId === item.id ? t("common.loading") : t("testcase.download")}
                  </Button>
                ) : null}
                {onActivate && !item.active && !item.archivedAt ? (
                  <Button size="sm" variant="outline" disabled={item.status !== "READY" || activatingId === item.id} onClick={() => onActivate(item)}>
                    {t("testcase.activate")}
                  </Button>
                ) : null}
                {onArchive && !item.active && !item.archivedAt ? (
                  <Button size="sm" variant="outline" disabled={busyId === item.id || item.status === "UPLOADING" || item.status === "PROCESSING"} onClick={() => onArchive(item)}>
                    {t("common.archive")}
                  </Button>
                ) : null}
                {onRestore && item.archivedAt ? (
                  <Button size="sm" variant="outline" disabled={busyId === item.id} onClick={() => onRestore(item)}>
                    {t("common.restore")}
                  </Button>
                ) : null}
                {onDelete && item.archivedAt ? (
                  <Button size="sm" variant="outline" className="text-red-700 hover:bg-red-50" disabled={busyId === item.id} onClick={() => onDelete(item)}>
                    {t("common.delete")}
                  </Button>
                ) : null}
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="rounded-xl border border-dashed border-[var(--oj-border)] bg-white/70 p-4 text-sm text-[var(--oj-ink-muted)]">{t("testcase.noPackages")}</div>
      )}
    </section>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-[var(--oj-ink-muted)]">{label}</dt>
      <dd className="mt-1 truncate text-sm font-medium tabular-nums text-[var(--oj-ink)]">{value}</dd>
    </div>
  );
}

function TestcaseManifestEditor({
  draft,
  filePaths,
  onChange,
  onRemoveCase
}: {
  draft: ManifestDraft;
  filePaths: string[];
  onChange: React.Dispatch<React.SetStateAction<ManifestDraft>>;
  onRemoveCase: (index: number) => void;
}) {
  const { t } = useI18n();
  function updateCase(index: number, patch: Partial<ManifestDraftCase>) {
    onChange((current) => ({
      ...current,
      cases: current.cases.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item)
    }));
  }
  function setCheckerType(type: ManifestDraftChecker["type"]) {
    onChange((current) => ({
      ...current,
      checker: {
        type,
        language: "cpp",
        source: type === "CUSTOM" ? current.checker.source || "checker/checker.cpp" : "",
        protocol: "AIOJ_JSON"
      }
    }));
  }

  return (
    <section className="mt-5 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("testcase.manifestTitle")}</h3>
          <p className="mt-1 max-w-[75ch] text-pretty text-sm leading-6 text-[var(--oj-ink-muted)]">{t("testcase.manifestHint")}</p>
          <p className="mt-1 text-sm leading-6 text-amber-900">{t("testcase.manifestScoreHint")}</p>
        </div>
      </div>

      <label className="mt-4 block max-w-md">
        <span className="mb-1.5 block text-sm font-medium text-[var(--oj-ink)]">{t("testcase.manifestVersionLabel")}</span>
        <input
          className={inputClass}
          value={draft.version}
          placeholder={t("testcase.manifestVersionPlaceholder")}
          onChange={(event) => onChange((current) => ({ ...current, version: event.target.value }))}
        />
      </label>

      <div className="mt-4 grid gap-4">
        <div className="rounded-xl border border-[var(--oj-border)] bg-white p-3">
          <div className="grid gap-3 sm:grid-cols-[180px_minmax(0,1fr)]">
            <CompactField label={t("testcase.manifestCheckerType")}>
              <select className={selectClass} value={draft.checker.type} onChange={(event) => setCheckerType(event.target.value as ManifestDraftChecker["type"])}>
                <option value="STANDARD">{t("testcase.manifestCheckerStandard")}</option>
                <option value="CUSTOM">{t("testcase.manifestCheckerCustom")}</option>
              </select>
            </CompactField>
            {draft.checker.type === "CUSTOM" ? (
              <CompactField label={t("testcase.manifestCheckerSource")}>
                <PathSelect
                  value={draft.checker.source}
                  filePaths={filePaths}
                  onChange={(value) => onChange((current) => ({ ...current, checker: { ...current.checker, source: value } }))}
                />
              </CompactField>
            ) : (
              <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-2 text-sm leading-6 text-[var(--oj-ink-muted)]">
                {t("testcase.manifestCheckerStandardHint")}
              </div>
            )}
          </div>
          {draft.checker.type === "CUSTOM" ? (
            <p className="mt-2 text-xs leading-5 text-[var(--oj-ink-muted)]">
              {t("testcase.manifestCheckerProtocolHint")}
            </p>
          ) : null}
        </div>
      </div>

      <div className="mt-4 flex items-center justify-between text-sm">
        <strong className="text-[var(--oj-ink)]">{t("testcase.manifestDetectedCount", { n: draft.cases.length })}</strong>
        {!draft.cases.length ? <span className="text-[var(--oj-ink-muted)]">{t("testcase.manifestEmpty")}</span> : null}
      </div>

      {draft.cases.length ? (
        <>
          <div className="mt-3 hidden overflow-x-auto rounded-xl border border-[var(--oj-border)] bg-white lg:block">
            <table className="min-w-[860px] w-full text-sm">
              <thead className="bg-[var(--oj-surface-muted)] text-xs text-[var(--oj-ink-muted)]">
                <tr>
                  <th className="w-40 px-3 py-3 text-left">{t("testcase.manifestCaseName")}</th>
                  <th className="px-3 py-3 text-left">{t("testcase.manifestCaseInput")}</th>
                  <th className="px-3 py-3 text-left">{t("testcase.manifestCaseOutput")}</th>
                  <th className="w-28 px-3 py-3 text-left">{t("testcase.manifestCaseScore")}</th>
                  <th className="w-24 px-3 py-3 text-right">{t("common.actions")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--oj-border-soft)]">
                {draft.cases.map((item, index) => (
                  <tr key={item.id}>
                    <td className="px-3 py-3">
                      <input className={cn(inputClass, "h-9")} value={item.name} onChange={(event) => updateCase(index, { name: event.target.value })} />
                    </td>
                    <td className="px-3 py-3">
                      <PathSelect value={item.input} filePaths={filePaths} onChange={(value) => updateCase(index, { input: value })} />
                    </td>
                    <td className="px-3 py-3">
                      <PathSelect value={item.output} filePaths={filePaths} onChange={(value) => updateCase(index, { output: value })} />
                    </td>
                    <td className="px-3 py-3">
                      <input
                        className={cn(inputClass, "h-9")}
                        inputMode="decimal"
                        type="number"
                        min={0}
                        step={1}
                        value={item.score ?? ""}
                        onChange={(event) => updateCase(index, { score: event.target.value === "" ? null : Number(event.target.value) })}
                      />
                    </td>
                    <td className="px-3 py-3 text-right">
                      <Button type="button" size="sm" variant="ghost" className="text-red-700 hover:text-red-800" onClick={() => onRemoveCase(index)}>
                        {t("testcase.manifestRemoveCase")}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-3 space-y-3 lg:hidden">
            {draft.cases.map((item, index) => (
              <article key={item.id} className="rounded-xl border border-[var(--oj-border)] bg-white p-3">
                <div className="mb-3 flex items-center justify-between gap-3">
                  <strong className="text-sm text-[var(--oj-ink)]">{item.name || t("testcase.manifestCaseName")}</strong>
                  <Button type="button" size="sm" variant="ghost" className="text-red-700 hover:text-red-800" onClick={() => onRemoveCase(index)}>
                    {t("testcase.manifestRemoveCase")}
                  </Button>
                </div>
                <div className="grid gap-3">
                  <CompactField label={t("testcase.manifestCaseName")}>
                    <input className={inputClass} value={item.name} onChange={(event) => updateCase(index, { name: event.target.value })} />
                  </CompactField>
                  <CompactField label={t("testcase.manifestCaseInput")}>
                    <PathSelect value={item.input} filePaths={filePaths} onChange={(value) => updateCase(index, { input: value })} />
                  </CompactField>
                  <CompactField label={t("testcase.manifestCaseOutput")}>
                    <PathSelect value={item.output} filePaths={filePaths} onChange={(value) => updateCase(index, { output: value })} />
                  </CompactField>
                  <CompactField label={t("testcase.manifestCaseScore")}>
                    <input
                      className={inputClass}
                      inputMode="decimal"
                      type="number"
                      min={0}
                      step={1}
                      value={item.score ?? ""}
                      onChange={(event) => updateCase(index, { score: event.target.value === "" ? null : Number(event.target.value) })}
                    />
                  </CompactField>
                </div>
              </article>
            ))}
          </div>
        </>
      ) : null}
    </section>
  );
}

function CompactField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-sm font-medium text-[var(--oj-ink)]">{label}</span>
      {children}
    </label>
  );
}

function PathSelect({ value, filePaths, onChange }: { value: string; filePaths: string[]; onChange: (value: string) => void }) {
  return (
    <select className={cn(selectClass, "h-9")} value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="">-</option>
      {filePaths.map((path) => (
        <option key={path} value={path}>{path}</option>
      ))}
    </select>
  );
}

async function sha256(blob: Blob) {
  const buffer = await blob.arrayBuffer();
  const digest = globalThis.crypto?.subtle?.digest
    ? await globalThis.crypto.subtle.digest("SHA-256", buffer)
    : sha256Fallback(new Uint8Array(buffer));
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function sha256Fallback(bytes: Uint8Array) {
  const paddedLength = (((bytes.length + 9 + 63) >> 6) << 6);
  const padded = new Uint8Array(paddedLength);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  const bitLength = bytes.length * 8;
  const view = new DataView(padded.buffer);
  view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000), false);
  view.setUint32(paddedLength - 4, bitLength >>> 0, false);

  let h0 = 0x6a09e667;
  let h1 = 0xbb67ae85;
  let h2 = 0x3c6ef372;
  let h3 = 0xa54ff53a;
  let h4 = 0x510e527f;
  let h5 = 0x9b05688c;
  let h6 = 0x1f83d9ab;
  let h7 = 0x5be0cd19;
  const words = new Uint32Array(64);

  for (let offset = 0; offset < paddedLength; offset += 64) {
    for (let index = 0; index < 16; index += 1) {
      words[index] = view.getUint32(offset + index * 4, false);
    }
    for (let index = 16; index < 64; index += 1) {
      const s0 = rotateRight(words[index - 15], 7) ^ rotateRight(words[index - 15], 18) ^ (words[index - 15] >>> 3);
      const s1 = rotateRight(words[index - 2], 17) ^ rotateRight(words[index - 2], 19) ^ (words[index - 2] >>> 10);
      words[index] = (words[index - 16] + s0 + words[index - 7] + s1) >>> 0;
    }

    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    let f = h5;
    let g = h6;
    let h = h7;

    for (let index = 0; index < 64; index += 1) {
      const s1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = (h + s1 + ch + SHA256_K[index] + words[index]) >>> 0;
      const s0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (s0 + maj) >>> 0;
      h = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }

    h0 = (h0 + a) >>> 0;
    h1 = (h1 + b) >>> 0;
    h2 = (h2 + c) >>> 0;
    h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0;
    h5 = (h5 + f) >>> 0;
    h6 = (h6 + g) >>> 0;
    h7 = (h7 + h) >>> 0;
  }

  const digest = new ArrayBuffer(32);
  const digestView = new DataView(digest);
  [h0, h1, h2, h3, h4, h5, h6, h7].forEach((word, index) => digestView.setUint32(index * 4, word, false));
  return digest;
}

function rotateRight(value: number, bits: number) {
  return (value >>> bits) | (value << (32 - bits));
}

const SHA256_K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]);

async function pollUntilReady(problemId: EntityId, uploadId: string) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    await new Promise((resolve) => window.setTimeout(resolve, 1500));
    const status = await api.testcaseUploadStatus(problemId, uploadId);
    if (status.status === "READY") return;
    if (status.status === "FAILED") throw new Error("Testcase package failed");
  }
}

async function failUploadSession(problemId: EntityId, uploadId: string) {
  try {
    await api.failTestcaseUpload(problemId, uploadId, {
      message: "Testcase upload was interrupted. Please upload the package again."
    });
  } catch {
    // Best-effort backend cleanup; keep the original upload error visible.
  }
}

function phaseLabel(phase: string, t: (key: string) => string) {
  if (phase === "scanning") return t("testcase.manifestScanning");
  if (phase === "building") return t("testcase.manifestRebuilding");
  if (phase === "hashing") return t("testcase.computing");
  if (phase === "uploading") return t("testcase.uploading");
  if (phase === "completing") return t("testcase.completing");
  if (phase === "polling") return t("testcase.polling");
  if (phase === "ready") return t("testcase.uploadReady");
  if (phase === "failed") return t("testcase.uploadFailed");
  return "-";
}

async function analyzeZip(file: File, t: (key: string, params?: Record<string, string | number>) => string): Promise<{ entries: ZipEntryInfo[]; hasExistingManifest: boolean }> {
  const zip = await JSZip.loadAsync(file);
  const entries: ZipEntryInfo[] = [];
  let hasExistingManifest = false;
  let totalUncompressedBytes = 0;
  zip.forEach((path, entry) => {
    const normalizedPath = normalizePath(path);
    const entryWithData = entry as { _data?: { uncompressedSize?: number } };
    const sizeBytes = entryWithData._data?.uncompressedSize ?? 0;
    if (normalizedPath === MANIFEST_PATH && !entry.dir) {
      hasExistingManifest = true;
      if (sizeBytes > MAX_MANIFEST_BYTES) {
        throw new Error(t("testcase.manifestTooLarge", { limit: formatBytes(MAX_MANIFEST_BYTES) }));
      }
    }
    if (!entry.dir) {
      totalUncompressedBytes += sizeBytes;
      if (totalUncompressedBytes > MAX_PACKAGE_BYTES) {
        throw new Error(t("testcase.packageUncompressedTooLarge", { limit: formatBytes(MAX_PACKAGE_BYTES) }));
      }
      if (normalizedPath !== MANIFEST_PATH && sizeBytes > MAX_CASE_FILE_BYTES) {
        throw new Error(t("testcase.caseFileTooLarge", {
          file: normalizedPath,
          limit: formatBytes(MAX_CASE_FILE_BYTES)
        }));
      }
    }
    entries.push({
      path: normalizedPath,
      sizeBytes,
      isDir: entry.dir
    });
  });
  return { entries, hasExistingManifest };
}

function sortedFilePaths(entries: ZipEntryInfo[]) {
  return entries
    .filter((entry) => !entry.isDir && normalizePath(entry.path) !== MANIFEST_PATH)
    .map((entry) => normalizePath(entry.path))
    .sort((left, right) => left.localeCompare(right));
}

function emptyManifestDraft(): ManifestDraft {
  return {
    version: "",
    checker: {
      type: "STANDARD",
      language: "cpp",
      source: "",
      protocol: "AIOJ_JSON"
    },
    subtasks: [],
    cases: []
  };
}

function inferDraft(entries: ZipEntryInfo[]): ManifestDraft {
  const pairs = inferPairs(sortedFilePaths(entries));
  return {
    version: `v1-${Date.now()}`,
    checker: {
      type: "STANDARD",
      language: "cpp",
      source: "",
      protocol: "AIOJ_JSON"
    },
    subtasks: [],
    cases: pairs.map(([input, output], index) => buildCase(input, output, index))
  };
}

function inferPairs(files: string[]): Array<[string, string]> {
  return inferDotInOutPairs(files) || inferInputOutputFolderPairs(files) || inferCasesFolderPairs(files) || inferFallbackPairs(files);
}

function inferDotInOutPairs(files: string[]) {
  const outputs = new Map<string, string>();
  files.filter((path) => /\.out$/i.test(path)).forEach((path) => outputs.set(path.replace(/\.out$/i, ""), path));
  const pairs = files
    .filter((path) => /\.in$/i.test(path))
    .map((input) => [input, outputs.get(input.replace(/\.in$/i, ""))] as const)
    .filter((pair): pair is readonly [string, string] => Boolean(pair[1]))
    .map(([input, output]) => [input, output] as [string, string]);
  return pairs.length ? pairs : null;
}

function inferInputOutputFolderPairs(files: string[]) {
  const outputs = new Map<string, string>();
  files.forEach((path) => {
    const match = path.match(/(?:^|\/)output\/(.+\.txt)$/i);
    if (match) outputs.set(match[1], path);
  });
  const pairs = files
    .map((input) => {
      const match = input.match(/(?:^|\/)input\/(.+\.txt)$/i);
      return match ? [input, outputs.get(match[1])] as const : null;
    })
    .filter((pair): pair is readonly [string, string] => Boolean(pair?.[1]))
    .map(([input, output]) => [input, output] as [string, string]);
  return pairs.length ? pairs : null;
}

function inferCasesFolderPairs(files: string[]) {
  const outputs = new Map<string, string>();
  files.forEach((path) => {
    const match = path.match(/(?:^|\/)cases\/([^/]+)\/output\.txt$/i);
    if (match) outputs.set(match[1], path);
  });
  const pairs = files
    .map((input) => {
      const match = input.match(/(?:^|\/)cases\/([^/]+)\/input\.txt$/i);
      return match ? [input, outputs.get(match[1])] as const : null;
    })
    .filter((pair): pair is readonly [string, string] => Boolean(pair?.[1]))
    .map(([input, output]) => [input, output] as [string, string]);
  return pairs.length ? pairs : null;
}

function inferFallbackPairs(files: string[]) {
  const pairs: Array<[string, string]> = [];
  for (let index = 0; index + 1 < files.length; index += 2) {
    pairs.push([files[index], files[index + 1]]);
  }
  return pairs;
}

function buildCase(input: string, output: string, index: number): ManifestDraftCase {
  return {
    id: `${index}-${input}-${output}`,
    name: fileNameWithoutExt(input) || `case-${index + 1}`,
    input,
    output,
    sample: false,
    subtaskKey: "",
    score: 1
  };
}

function removeManifestCase(draft: ManifestDraft, index: number): ManifestDraft {
  const cases = draft.cases.filter((_, itemIndex) => itemIndex !== index);
  return { ...draft, cases };
}

function validateManifestDraft(draft: ManifestDraft, filePaths: string[], t: (key: string) => string) {
  if (!draft.version.trim() || draft.cases.length === 0) {
    return t("testcase.manifestInvalid");
  }
  const validPaths = new Set(filePaths);
  if (draft.checker.type === "CUSTOM" && !validPaths.has(draft.checker.source)) {
    return t("testcase.manifestInvalidCheckerSource");
  }
  for (const item of draft.cases) {
    if (!validPaths.has(item.input) || !validPaths.has(item.output)) {
      return t("testcase.manifestInvalidPath");
    }
    if (item.score != null && (!Number.isInteger(item.score) || item.score < 0)) {
      return t("testcase.manifestInvalidScore");
    }
  }
  return "";
}

async function injectManifest(file: File, draft: ManifestDraft): Promise<Blob> {
  const zip = await JSZip.loadAsync(file);
  const payload = {
    version: draft.version.trim(),
    checker: draft.checker.type === "CUSTOM" ? {
      type: "CUSTOM",
      language: "cpp",
      source: draft.checker.source.trim(),
      protocol: "AIOJ_JSON"
    } : {
      type: "STANDARD"
    },
    subtasks: undefined,
    cases: draft.cases.map((item) => ({
      name: item.name.trim() || undefined,
      input: item.input,
      output: item.output,
      sample: item.sample,
      score: item.score ?? undefined
    }))
  };
  zip.file(MANIFEST_PATH, JSON.stringify(payload, null, 2));
  return zip.generateAsync({ type: "blob", compression: "DEFLATE", compressionOptions: { level: 6 } });
}

function normalizePath(value: string) {
  return value.replace(/\\/g, "/").replace(/^\.?\//, "");
}

function fileNameWithoutExt(path: string) {
  const fileName = normalizePath(path).split("/").pop() || path;
  return fileName.replace(/\.[^.]+$/, "");
}

function readableUploadError(caught: unknown, fallback: string, t: (key: string) => string, locale: "zh-CN" | "en-US") {
  if (caught instanceof ApiError && caught.errorKey === "testcase.manifestRequired") {
    return t("testcase.manifestRequiredUploadHint");
  }
  const message = readableCaughtError(caught, locale, fallback);
  return message || fallback;
}
