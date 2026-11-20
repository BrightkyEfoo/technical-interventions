export interface Draft {
  requestId: string;
  baseVersion: number;
  key: string;
  report: string;
  attempted: boolean;
}

export function createDraft(requestId: string, version: number, key: string): Draft {
  return { requestId, baseVersion: version, key, report: '', attempted: false };
}

export function sendable(draft: Draft, currentVersion: number) {
  return draft.attempted || draft.baseVersion === currentVersion;
}

export function rebase(draft: Draft, version: number, key: string): Draft {
  return { ...draft, baseVersion: version, key, attempted: false };
}
