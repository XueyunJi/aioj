import { api, operationJobPollingDelay, type ContestExportResponse, type EntityId, type OperationJobResponse } from "@aioj/api-client";
import { getStoredLocale, resolveStoredErrorMessageForLocale } from "@aioj/i18n/vanilla";

type OperationJobGetter = (jobId: EntityId) => Promise<OperationJobResponse>;

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

export function downloadExportResponse(exportFile: ContestExportResponse) {
  const bytes = Uint8Array.from(atob(exportFile.base64Content), (char) => char.charCodeAt(0));
  const blob = new Blob([bytes], { type: exportFile.contentType });
  const url = URL.createObjectURL(blob);

  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = exportFile.fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
}

export async function downloadOperationJobArtifact(jobId: EntityId) {
  const artifact = await api.operationJobArtifact(jobId);
  downloadExportResponse(artifact);
}

export async function waitForOperationJobArtifactAndDownload({
  jobId,
  getJob,
  timeoutMs = 120000
}: {
  jobId: EntityId;
  getJob: OperationJobGetter;
  timeoutMs?: number;
}) {
  const startedAt = Date.now();

  while (Date.now() - startedAt <= timeoutMs) {
    const job = await getJob(jobId);

    if (job.status === "COMPLETED") {
      if (job.artifact) {
        await downloadOperationJobArtifact(jobId);
      }
      return job;
    }

    if (job.status === "FAILED" || job.status === "CANCELLED") {
      throw new Error(resolveStoredErrorMessageForLocale(getStoredLocale(), job.errorMessage ?? job.status, "operation"));
    }

    await delay(operationJobPollingDelay(Date.now() - startedAt));
  }

  throw new Error(resolveStoredErrorMessageForLocale(getStoredLocale(), "Operation job polling timed out", "operation"));
}
