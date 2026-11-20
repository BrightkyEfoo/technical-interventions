import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { App } from '@capacitor/app';
import { Browser } from '@capacitor/browser';
import { OidcClient, User, UserManager, WebStorageStateStore } from 'oidc-client-ts';

export const statuses = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const;
export type Status = (typeof statuses)[number];
export type Role = 'REQUESTER' | 'TECHNICIAN' | 'MANAGER' | 'SUPER_ADMIN';
export type StaffRole = 'MANAGER' | 'TECHNICIAN';
export interface StaffRequest {
  role: StaffRole;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  temporaryPassword: string;
}
export interface AccountSummary {
  id: string;
  username: string;
  email: string | null;
  firstName: string | null;
  lastName: string | null;
  role: Role | null;
  enabled: boolean;
}
export interface AccountPage {
  items: AccountSummary[];
  page: number;
  size: number;
  hasNext: boolean;
}
export interface TechnicianSummary { id: string; name: string; }
export type Priority = 'LOW' | 'NORMAL' | 'HIGH';

export const statusLabel: Record<Status, string> = {
  OPEN: 'À affecter', ASSIGNED: 'Affectée', IN_PROGRESS: 'En cours', RESOLVED: 'Résolue', CLOSED: 'Clôturée',
};

export interface Intervention {
  id: string;
  title: string;
  status: Status;
  ownerId: string | null;
  assigneeId: string | null;
  priority: Priority;
  version: number;
  createdAt: string;
  report: string | null;
}

export interface Page {
  items: Intervention[];
  total: number;
  page: number;
  size: number;
}

export interface Comment {
  id: string;
  authorId: string;
  body: string;
  createdAt: string;
}

export interface HistoryEntry {
  version: number;
  actorId: string;
  action: string;
  status: Status;
  occurredAt: string;
}

export interface Notification {
  id: string;
  requestId: string;
  createdAt: string;
}

export const directory = [
  { id: '00000000-0000-4000-8000-000000000001', name: 'manager', role: 'MANAGER' },
  { id: '00000000-0000-4000-8000-000000000002', name: 'technician', role: 'TECHNICIAN' },
  { id: '00000000-0000-4000-8000-000000000003', name: 'requester', role: 'REQUESTER' },
  { id: '00000000-0000-4000-8000-000000000004', name: 'technician2', role: 'TECHNICIAN' },
] as const;
export const technicians = directory.filter(account => account.role === 'TECHNICIAN');

export function errorMessage(error: unknown): string {
  const response = error as { status?: number; error?: { code?: string; detail?: string } };
  if (response.status === 0) return 'Serveur injoignable. Vérifie la connexion.';
  if (response.status === 401) return 'Ta session a expiré. Reconnecte-toi.';
  if (response.status === 403) return 'Cette action est réservée à un autre rôle.';
  if (response.status === 404) return 'Cette demande est introuvable ou inaccessible.';
  if (response.status === 409) return response.error?.detail ?? 'La demande a changé. Recharge son état avant de confirmer.';
  return response.error?.detail ?? 'L’opération a échoué.';
}

@Injectable({ providedIn: 'root' })
export class Session {
  private manager?: UserManager;
  private nativeClient?: OidcClient;
  private nativeCallback?: () => Promise<void>;
  readonly user = signal<User | null>(null);
  readonly demo = signal<(typeof directory)[number] | null>(null);
  readonly variant = signal<'java' | 'kotlin'>(localStorage.getItem('serviceops.variant') === 'kotlin' ? 'kotlin' : 'java');

  get accountId(): string | null {
    return this.user()?.profile.sub ?? this.demo()?.id ?? null;
  }

  get role(): Role | null {
    const user = this.user();
    const claims = user?.profile;
    let realm = claims?.['realm_access'] as { roles?: Role[] } | undefined;
    if (user && !realm) {
      try {
        const payload = user.access_token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
        realm = JSON.parse(atob(payload)).realm_access;
      } catch {
        return null;
      }
    }
    if (realm?.roles) {
      return (['SUPER_ADMIN', 'MANAGER', 'TECHNICIAN', 'REQUESTER'] as const).find(role => realm.roles?.includes(role)) ?? null;
    }
    return this.demo()?.role ?? directory.find(account => account.id === claims?.sub)?.role ?? null;
  }

  get apiUrl(): string {
    const variant = this.variant();
    const explicitHost = localStorage.getItem('serviceops.apiHost');
    const explicitPort = localStorage.getItem(`serviceops.${variant}Port`);
    if (!Capacitor.isNativePlatform() && !explicitHost && !explicitPort) {
      return `/backend/${variant}/api/v1`;
    }
    const host = explicitHost ?? 'http://127.0.0.1';
    const port = explicitPort ?? (variant === 'java' ? '8080' : '8081');
    return `${host}:${port}/api/v1`;
  }

  selectVariant(variant: 'java' | 'kotlin') {
    this.variant.set(variant);
    localStorage.setItem('serviceops.variant', variant);
  }

  private configure(clientId: string) {
    this.manager = new UserManager({
      authority: localStorage.getItem('serviceops.authority') ?? 'http://localhost:8180/realms/serviceops',
      client_id: clientId,
      redirect_uri: window.location.origin + '/',
      post_logout_redirect_uri: window.location.origin + '/',
      response_type: 'code',
      scope: 'openid profile',
      automaticSilentRenew: false,
      userStore: new WebStorageStateStore({ store: sessionStorage }),
    });
  }

  async initialize(clientId: string, nativeCallback?: () => Promise<void>) {
    if (Capacitor.isNativePlatform()) {
      this.nativeCallback = nativeCallback;
      this.nativeClient = new OidcClient({
        authority: localStorage.getItem('serviceops.authority') ?? 'http://localhost:8180/realms/serviceops',
        client_id: clientId,
        redirect_uri: 'cm.brightky.serviceops://oidc',
        response_type: 'code',
        scope: 'openid profile',
        stateStore: new WebStorageStateStore({ store: sessionStorage }),
      });
      await App.addListener('appUrlOpen', ({ url }) => { void this.completeNativeLogin(url); });
      const launched = await App.getLaunchUrl();
      if (launched?.url) await this.completeNativeLogin(launched.url);
      return;
    }
    this.configure(clientId);
    const parameters = new URLSearchParams(location.search);
    if (parameters.has('code') && parameters.has('state')) {
      try {
        this.user.set(await this.manager!.signinRedirectCallback());
      } finally {
        history.replaceState({}, '', '/');
      }
    } else {
      this.user.set(await this.manager!.getUser());
    }
  }

  private async completeNativeLogin(url: string) {
    if (!url.startsWith('cm.brightky.serviceops://oidc?') || !this.nativeClient) return;
    try {
      const result = await this.nativeClient.processSigninResponse(url);
      this.user.set(new User(result));
      await this.nativeCallback?.();
    } finally {
      await Browser.close();
    }
  }

  async login(register = false) {
    if (this.nativeClient) {
      const request = await this.nativeClient.createSigninRequest({ prompt: register ? 'create' : 'login' });
      await Browser.open({ url: request.url });
      return;
    }
    if (!this.manager) throw new Error('Configure une origine HTTP pour la connexion de démonstration.');
    await this.manager.signinRedirect(register ? { prompt: 'create' } : {});
  }

  useDemo(id: string) {
    this.demo.set(directory.find(account => account.id === id) ?? null);
  }

  async logout() {
    this.demo.set(null);
    const currentUser = this.user();
    this.user.set(null);
    if (currentUser) await this.manager?.signoutRedirect();
  }

  headers(key?: string): HttpHeaders {
    let headers = new HttpHeaders();
    const user = this.user();
    const demo = this.demo();
    if (user && !user.expired) headers = headers.set('Authorization', `Bearer ${user.access_token}`);
    if (demo) headers = headers.set('X-Demo-Actor', demo.id);
    if (key) headers = headers.set('Idempotency-Key', key);
    return headers;
  }
}

@Injectable({ providedIn: 'root' })
export class ServiceOpsApi {
  private http = inject(HttpClient);
  private session = inject(Session);
  private get url() { return this.session.apiUrl; }

  createStaff(account: StaffRequest) {
    return this.http.post<{ id: string; username: string; email: string; role: StaffRole }>(
      this.url + '/admin/accounts', account, { headers: this.session.headers() },
    );
  }

  listAccounts(query: string, page: number) {
    return this.http.get<AccountPage>(this.url + '/admin/accounts', {
      headers: this.session.headers(), params: { query, page },
    });
  }

  technicians() {
    return this.http.get<TechnicianSummary[]>(this.url + '/technicians', { headers: this.session.headers() });
  }

  list(query = '', status: Status | '' = '', page = 0, size = 20) {
    return this.http.get<Page>(this.url + '/requests', {
      headers: this.session.headers(),
      params: { query, ...(status ? { status } : {}), page, size },
    });
  }

  detail(id: string) {
    return this.http.get<Intervention>(`${this.url}/requests/${id}`, { headers: this.session.headers() });
  }

  create(title: string, priority: Priority, key: string) {
    return this.http.post<Intervention>(this.url + '/requests', { title, priority }, { headers: this.session.headers(key) });
  }

  assign(request: Intervention, technicianId: string) {
    return this.http.post<void>(`${this.url}/requests/${request.id}/assign`, {
      expectedVersion: request.version, technicianId,
    }, { headers: this.session.headers() });
  }

  transition(request: Intervention, action: 'start' | 'close') {
    return this.http.post<Intervention>(`${this.url}/requests/${request.id}/${action}`, {
      expectedVersion: request.version,
    }, { headers: this.session.headers() });
  }

  resolve(id: string, expectedVersion: number, report: string, key: string) {
    return this.http.post<Intervention>(`${this.url}/requests/${id}/resolve`, {
      expectedVersion, report,
    }, { headers: this.session.headers(key) });
  }

  comments(id: string) {
    return this.http.get<Comment[]>(`${this.url}/requests/${id}/comments`, { headers: this.session.headers() });
  }

  history(id: string) {
    return this.http.get<HistoryEntry[]>(`${this.url}/requests/${id}/history`, { headers: this.session.headers() });
  }

  comment(request: Intervention, body: string) {
    return this.http.post<Comment>(`${this.url}/requests/${request.id}/comments`, {
      expectedVersion: request.version, body,
    }, { headers: this.session.headers() });
  }

  attach(request: Intervention, image: Blob) {
    const body = new FormData();
    body.set('file', image, image.type === 'image/png' ? 'photo.png' : 'photo.jpg');
    body.set('expectedVersion', String(request.version));
    return this.http.post<{ id: string }>(`${this.url}/requests/${request.id}/attachments`, body, {
      headers: this.session.headers(),
    });
  }

  notifications() {
    return this.http.get<Notification[]>(this.url + '/notifications', { headers: this.session.headers() });
  }
}
