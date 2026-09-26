import { defineStore } from 'pinia';

export interface AiFileContext {
  uniqueName: string;
  originalName: string;
  type: string;
  size: number;
}

export interface AiMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
}

interface AiConversationSnapshot {
  context: AiFileContext;
  sessionId: string | null;
  messages: AiMessage[];
  updatedAt: number;
}

interface AiPersistedState {
  activeKey: string | null;
  conversations: Record<string, AiConversationSnapshot>;
}

const STORAGE_KEY = 'jifa.ai.conversations.v1';
const MAX_CONVERSATIONS = 12;
const MAX_MESSAGES_PER_CONVERSATION = 40;
const MAX_MESSAGE_CHARS = 100_000;

function contextKey(context: AiFileContext | null): string | null {
  return context ? `${context.type}:${context.uniqueName}` : null;
}

export const useAiStore = defineStore('ai', {
  state: () => ({
    opened: false,
    context: null as AiFileContext | null,
    sessionId: null as string | null,
    messages: [] as AiMessage[],
    busy: false,
    progress: '',
    progressEvents: [] as string[],
    status: 'idle' as 'idle' | 'thinking' | 'analyzing' | 'streaming' | 'error',
    conversations: {} as Record<string, AiConversationSnapshot>,
    hydrated: false
  }),
  actions: {
    hydrate() {
      if (this.hydrated) return;
      this.hydrated = true;
      try {
        const raw = window.localStorage.getItem(STORAGE_KEY);
        if (!raw) return;
        const saved = JSON.parse(raw) as AiPersistedState;
        this.conversations = saved.conversations || {};
        const snapshot = saved.activeKey ? this.conversations[saved.activeKey] : undefined;
        if (snapshot) this.restore(snapshot);
      } catch (_) {
        window.localStorage.removeItem(STORAGE_KEY);
      }
    },
    setContext(context: AiFileContext | null) {
      this.persistCurrent();
      if (contextKey(this.context) === contextKey(context)) {
        this.context = context;
        this.persistCurrent();
        return;
      }
      this.context = context;
      this.progress = '';
      this.progressEvents = [];
      const snapshot = context ? this.conversations[contextKey(context)!] : undefined;
      if (snapshot) {
        this.restore(snapshot);
      } else {
        this.sessionId = null;
        this.messages = [];
        this.persistStorage();
      }
    },
    addMessage(message: AiMessage) {
      this.messages.push(message);
      this.persistCurrent();
    },
    setSessionId(sessionId: string) {
      this.sessionId = sessionId;
      this.persistCurrent();
    },
    clear() {
      const key = contextKey(this.context);
      this.sessionId = null;
      this.progress = '';
      this.progressEvents = [];
      this.messages = [];
      if (key && this.context) {
        this.conversations[key] = {
          context: { ...this.context },
          sessionId: null,
          messages: [],
          updatedAt: Date.now()
        };
      }
      this.persistStorage();
    },
    restore(snapshot: AiConversationSnapshot) {
      this.context = snapshot.context;
      this.sessionId = snapshot.sessionId;
      this.messages = snapshot.messages || [];
      this.progress = '';
      this.progressEvents = [];
    },
    persistCurrent() {
      const key = contextKey(this.context);
      if (!key || !this.context) {
        this.persistStorage();
        return;
      }
      this.conversations[key] = {
        context: { ...this.context },
        sessionId: this.sessionId,
        messages: this.messages.map((message) => ({ ...message })),
        updatedAt: Date.now()
      };
      this.persistStorage();
    },
    persistStorage() {
      if (!this.hydrated) return;
      const entries = Object.entries(this.conversations)
        .sort((left, right) => right[1].updatedAt - left[1].updatedAt)
        .slice(0, MAX_CONVERSATIONS);
      const conversations = Object.fromEntries(entries.map(([key, snapshot]) => [key, {
        ...snapshot,
        messages: snapshot.messages.slice(-MAX_MESSAGES_PER_CONVERSATION).map((message) => ({
          ...message,
          content: message.content.slice(0, MAX_MESSAGE_CHARS)
        }))
      }])) as Record<string, AiConversationSnapshot>;
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
          activeKey: contextKey(this.context),
          conversations
        } satisfies AiPersistedState));
      } catch (_) {
        // Storage can be disabled by the browser or reject writes when its quota is exhausted.
      }
    }
  }
});
