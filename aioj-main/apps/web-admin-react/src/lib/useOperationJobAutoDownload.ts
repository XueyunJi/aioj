import * as React from "react";
import { api, type EntityId, type OperationJobResponse } from "@aioj/api-client";
import { waitForOperationJobArtifactAndDownload } from "./operationJobDownloads";

export function useOperationJobAutoDownload({
  contestId,
  onCompleted,
  onFailed
}: {
  contestId?: EntityId;
  onCompleted?: (job: OperationJobResponse) => void;
  onFailed?: (error: unknown) => void;
}) {
  const activeJobIdsRef = React.useRef(new Set<EntityId>());
  const mountedRef = React.useRef(true);

  React.useEffect(() => {
    return () => {
      mountedRef.current = false;
    };
  }, []);

  return React.useCallback((jobId: EntityId) => {
    if (!contestId || activeJobIdsRef.current.has(jobId)) return;

    activeJobIdsRef.current.add(jobId);
    void waitForOperationJobArtifactAndDownload({
      jobId,
      getJob: (id) => api.contestOperationJob(contestId, id)
    })
      .then((job) => {
        if (mountedRef.current) onCompleted?.(job);
      })
      .catch((error) => {
        if (mountedRef.current) onFailed?.(error);
      })
      .finally(() => {
        activeJobIdsRef.current.delete(jobId);
      });
  }, [contestId, onCompleted, onFailed]);
}
