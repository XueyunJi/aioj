import type { AiChatMessageResponse } from "@aioj/api-client";

export interface MergeableChatMessage extends AiChatMessageResponse {
  local?: boolean;
  status?: "sending" | "success" | "error";
  streamId?: string;
}

export function chatMessageKey(message: MergeableChatMessage) {
  if (message.id) return `server:${message.id}`;
  if (message.clientMessageId) return `client:${message.clientMessageId}`;
  if (message.streamId) return `stream:${message.streamId}`;
  return `fallback:${message.role}:${message.createdAt}:${hashText(message.content ?? "")}`;
}

export function mergeChatMessages<T extends MergeableChatMessage>(serverMessages: T[], localMessages: T[]) {
  const byKey = new Map<string, T>();
  for (const message of [...localMessages, ...serverMessages]) {
    const key = chatMessageKey(message);
    const existing = byKey.get(key);
    byKey.set(key, mergeMessage(existing, message));
  }

  const serverIdByClientId = new Map<string, string>();
  for (const message of byKey.values()) {
    if (message.id && message.clientMessageId && !message.local) {
      serverIdByClientId.set(message.clientMessageId, message.id);
    }
  }

  const normalized = new Map<string, T>();
  for (const message of byKey.values()) {
    const serverId = message.clientMessageId ? serverIdByClientId.get(message.clientMessageId) : null;
    const key = serverId ? `server:${serverId}` : chatMessageKey(message);
    normalized.set(key, mergeMessage(normalized.get(key), message));
  }

  return [...normalized.values()].sort(compareMessages);
}

function mergeMessage<T extends MergeableChatMessage>(existing: T | undefined, incoming: T): T {
  if (!existing) return incoming;
  const preferIncoming = !incoming.local || existing.local;
  return {
    ...existing,
    ...incoming,
    id: preferIncoming && incoming.id ? incoming.id : existing.id || incoming.id,
    clientMessageId: incoming.clientMessageId || existing.clientMessageId,
    content: incoming.content || existing.content,
    status: incoming.status === "error" ? "error" : existing.status === "sending" && !incoming.local ? "success" : incoming.status || existing.status,
    local: existing.local && incoming.local
  };
}

function hashText(value: string) {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) | 0;
  }
  return Math.abs(hash).toString(36);
}

function compareMessages(first: MergeableChatMessage, second: MergeableChatMessage) {
  const firstTurnKey = turnKey(first);
  const secondTurnKey = turnKey(second);

  if (firstTurnKey && firstTurnKey === secondTurnKey) {
    const role = roleOrder(first) - roleOrder(second);
    if (role !== 0) return role;
  }

  const firstTime = timestamp(first.createdAt);
  const secondTime = timestamp(second.createdAt);
  if (firstTime !== secondTime) return firstTime - secondTime;

  if (firstTurnKey && secondTurnKey && firstTurnKey !== secondTurnKey) {
    const turn = firstTurnKey.localeCompare(secondTurnKey);
    if (turn !== 0) return turn;
  }

  const role = roleOrder(first) - roleOrder(second);
  if (role !== 0) return role;

  return chatMessageKey(first).localeCompare(chatMessageKey(second));
}

function turnKey(message: MergeableChatMessage) {
  const clientMessageId = message.clientMessageId;
  if (!clientMessageId) return "";
  return clientMessageId.endsWith(":assistant") ? clientMessageId.slice(0, -":assistant".length) : clientMessageId;
}

function roleOrder(message: MergeableChatMessage) {
  if (message.role === "user") return 0;
  if (message.role === "assistant") return 1;
  return 2;
}

function timestamp(value: string) {
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : 0;
}
