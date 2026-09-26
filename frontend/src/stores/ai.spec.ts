import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';

import { useAiStore, type AiFileContext } from './ai';

const heap: AiFileContext = {
  uniqueName: 'heap-1',
  originalName: 'java_pid1.hprof',
  type: 'HEAP_DUMP',
  size: 1024
};

const gc: AiFileContext = {
  uniqueName: 'gc-1',
  originalName: 'gc.log',
  type: 'GC_LOG',
  size: 512
};

describe('AI conversation persistence', () => {
  beforeEach(() => {
    window.localStorage.clear();
    setActivePinia(createPinia());
  });

  it('restores messages and session id after a page refresh', () => {
    const first = useAiStore();
    first.hydrate();
    first.setContext(heap);
    first.setSessionId('session-1');
    first.addMessage({ role: 'user', content: '分析疑似内存泄漏' });
    first.addMessage({ role: 'assistant', content: '发现可疑强引用链' });

    setActivePinia(createPinia());
    const refreshed = useAiStore();
    refreshed.hydrate();

    expect(refreshed.context?.uniqueName).toBe('heap-1');
    expect(refreshed.sessionId).toBe('session-1');
    expect(refreshed.messages).toHaveLength(2);
    expect(refreshed.messages[1].content).toBe('发现可疑强引用链');
  });

  it('keeps independent histories when switching files and removes the active one on clear', () => {
    const store = useAiStore();
    store.hydrate();
    store.setContext(heap);
    store.addMessage({ role: 'user', content: 'heap question' });
    store.setContext(gc);
    store.addMessage({ role: 'user', content: 'gc question' });
    store.setContext(heap);

    expect(store.messages[0].content).toBe('heap question');
    store.clear();

    setActivePinia(createPinia());
    const refreshed = useAiStore();
    refreshed.hydrate();
    expect(refreshed.context?.uniqueName).toBe('heap-1');
    expect(refreshed.sessionId).toBeNull();
    expect(refreshed.messages).toHaveLength(0);
  });
});
