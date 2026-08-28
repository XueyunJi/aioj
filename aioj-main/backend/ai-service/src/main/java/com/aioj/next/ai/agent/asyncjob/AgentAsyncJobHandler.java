package com.aioj.next.ai.agent.asyncjob;

import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;

/** Handler contract for ai_async_jobs types; implementations are Spring beans auto-wired into the worker. */
public interface AgentAsyncJobHandler {

    String jobType();

    void handle(AiAsyncJobEntity job) throws Exception;
}
