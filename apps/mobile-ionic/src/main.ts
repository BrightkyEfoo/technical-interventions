import { provideHttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { bootstrapApplication } from '@angular/platform-browser';
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';
import { Capacitor } from '@capacitor/core';
import { Network } from '@capacitor/network';
import { Preferences } from '@capacitor/preferences';
import { IonApp, IonButton, IonCard, IonCardContent, IonContent, IonHeader, IonTitle, IonToolbar, provideIonicAngular } from '@ionic/angular/standalone';
import { EMPTY, Subject, catchError, concatMap, firstValueFrom, from } from 'rxjs';
import { ServiceOpsApi, Session, directory, errorMessage, statusLabel, type Intervention } from '../../shared/api';
import { createDraft, rebase, sendable, type Draft } from '../../shared/offline';

type StoredIdentity = { accountId: string; variant: 'java' | 'kotlin' };

@Component({
  selector: 'serviceops-root',
  imports: [FormsModule, ReactiveFormsModule, IonApp, IonButton, IonCard, IonCardContent, IonContent, IonHeader, IonTitle, IonToolbar],
  templateUrl: './mobile.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Mobile {
  private readonly api = inject(ServiceOpsApi);
  private readonly destroy = inject(DestroyRef);
  private readonly writes = new Subject<{ key: string; value: string | null; done?: () => void; failed?: (error: unknown) => void }>();
  readonly session = inject(Session);
  readonly native = Capacitor.isNativePlatform();
  readonly allowDemo = this.native || new URLSearchParams(location.search).has('demo');
  readonly technicians = directory.filter(user => user.role === 'TECHNICIAN');
  readonly statusLabel = statusLabel;
  readonly online = signal(navigator.onLine);
  readonly cachedIdentity = signal<StoredIdentity | null>(null);
  readonly requests = signal<Intervention[]>([]);
  readonly selected = signal<Intervention | null>(null);
  readonly draft = signal<Draft | null>(null);
  readonly conflict = signal<Intervention | null>(null);
  readonly error = signal('');
  readonly notice = signal('');
  readonly busy = signal(false);
  readonly report = new FormControl('', { nonNullable: true });
  readonly photo = signal<Blob | null>(null);
  readonly apiHost = new FormControl(localStorage.getItem('serviceops.apiHost') ?? 'http://127.0.0.1', { nonNullable: true });
  readonly authority = new FormControl(localStorage.getItem('serviceops.authority') ?? 'http://localhost:8180/realms/serviceops', { nonNullable: true });

  constructor() {
    this.writes.pipe(concatMap(change => from(this.persist(change).then(() => change.done?.())).pipe(catchError(error => {
      change.failed?.(error);
      this.error.set('Impossible d’enregistrer la copie locale.');
      return EMPTY;
    }))), takeUntilDestroyed(this.destroy)).subscribe();
    this.report.valueChanges.pipe(takeUntilDestroyed(this.destroy)).subscribe(report => {
      const draft = this.draft();
      if (draft && !draft.attempted) {
        this.storeDraft({ ...draft, report });
      }
    });
    this.initialize().catch(() => this.error.set('Impossible de préparer l’accès à la session.'));
  }

  private async initialize() {
    this.online.set((await Network.getStatus()).connected);
    const listener = await Network.addListener('networkStatusChange', network => {
      this.online.set(network.connected);
      if (network.connected && this.session.accountId) {
        this.notice.set('Connexion rétablie. Tu peux vérifier et envoyer ton brouillon.');
        void this.refresh();
      }
    });
    this.destroy.onDestroy(() => { void listener.remove(); });
    await this.session.initialize('serviceops-mobile', () => this.signedIn());
    const stored = await Preferences.get({ key: 'serviceops.last-mobile-account' });
    if (stored.value) this.cachedIdentity.set(JSON.parse(stored.value) as StoredIdentity);
    if (this.session.accountId) await this.signedIn();
    else if (this.cachedIdentity()) await this.readCache();
  }

  private identity() {
    const accountId = this.session.accountId;
    if (accountId) return { accountId, variant: this.session.variant() };
    return this.cachedIdentity();
  }

  private localKey(kind: string, requestId = '') {
    const identity = this.identity();
    if (!identity) throw new Error('Compte local introuvable');
    return `serviceops.${identity.variant}.${identity.accountId}.${kind}${requestId ? '.' + requestId : ''}`;
  }

  private async signedIn() {
    const identity = this.identity();
    if (!identity) return;
    this.cachedIdentity.set(identity);
    await Preferences.set({ key: 'serviceops.last-mobile-account', value: JSON.stringify(identity) });
    await this.readCache();
    if (this.online()) await this.refresh();
  }

  async login(register = false) {
    try { await this.session.login(register); }
    catch (error) { this.error.set(error instanceof Error ? error.message : 'Connexion impossible.'); }
  }

  async demo(id: string) {
    this.session.useDemo(id);
    await this.signedIn();
  }

  async changeBackend(variant: 'java' | 'kotlin') {
    this.session.selectVariant(variant);
    this.selected.set(null);
    await this.signedIn();
  }

  saveNativeSettings() {
    const host = this.apiHost.value.replace(/\/$/, '');
    try {
      const url = new URL(host);
      const authority = new URL(this.authority.value);
      if (!/^https?:$/.test(url.protocol) || url.port || url.pathname !== '/' ||
          !/^https?:$/.test(authority.protocol)) throw new Error();
      localStorage.setItem('serviceops.apiHost', host);
      localStorage.setItem('serviceops.authority', authority.toString());
      location.reload();
    } catch {
      this.error.set('Indique une origine HTTP(S) sans port pour le serveur API et l’URL complète du realm.');
    }
  }

  async logout() {
    await Preferences.remove({ key: 'serviceops.last-mobile-account' });
    this.cachedIdentity.set(null);
    this.selected.set(null);
    this.requests.set([]);
    this.draft.set(null);
    await this.session.logout();
  }

  private async readCache() {
    const cache = await Preferences.get({ key: this.localKey('interventions') });
    this.requests.set(cache.value ? JSON.parse(cache.value) as Intervention[] : []);
  }

  async refresh() {
    if (this.session.role === 'SUPER_ADMIN') return;
    if (!this.online() || !this.session.accountId) return;
    const key = this.localKey('interventions');
    try {
      const all: Intervention[] = [];
      let page = 0;
      let total: number;
      do {
        const result = await firstValueFrom(this.api.list('', '', page++, 100));
        all.push(...result.items);
        total = result.total;
        if (!result.items.length) break;
      } while (all.length < total);
      if (!this.identity() || key !== this.localKey('interventions')) return;
      this.requests.set(all);
      this.writes.next({ key, value: JSON.stringify(all) });
    } catch (error) {
      this.error.set(errorMessage(error));
    }
  }

  async open(request: Intervention) {
    this.error.set('');
    this.photo.set(null);
    this.conflict.set(null);
    this.selected.set(request);
    const stored = await Preferences.get({ key: this.localKey('draft', request.id) });
    if (this.selected()?.id !== request.id) return;
    const draft = stored.value ? JSON.parse(stored.value) as Draft : null;
    this.draft.set(draft);
    this.report.setValue(draft?.report ?? '', { emitEvent: false });
  }

  beginDraft() {
    const request = this.selected();
    if (!request) return;
    const draft = createDraft(request.id, request.version, crypto.randomUUID());
    this.report.setValue('', { emitEvent: false });
    this.storeDraft(draft);
  }

  private storeDraft(draft: Draft) {
    this.draft.set(draft);
    this.writes.next({ key: this.localKey('draft', draft.requestId), value: JSON.stringify(draft) });
  }

  private persist(change: { key: string; value: string | null }) {
    return change.value == null ? Preferences.remove({ key: change.key }) :
      Preferences.set({ key: change.key, value: change.value });
  }

  async start(request: Intervention) {
    await this.run(async () => {
      this.selected.set(await firstValueFrom(this.api.transition(request, 'start')));
      await this.refresh();
    });
  }

  async takePhoto() {
    try {
      const photo = await Camera.getPhoto({ source: CameraSource.Camera, resultType: CameraResultType.Uri, quality: 75 });
      if (photo.webPath) this.photo.set(await (await fetch(photo.webPath)).blob());
    } catch { this.error.set('Photo indisponible. Tu peux continuer le compte rendu.'); }
  }

  selectFile(event: Event) {
    const selectedFile = (event.target as HTMLInputElement).files?.item(0);
    if (selectedFile && (!['image/jpeg', 'image/png'].includes(selectedFile.type) || selectedFile.size > 5 * 1024 * 1024)) {
      this.error.set('Choisis une image JPEG ou PNG de 5 Mo maximum.');
      return;
    }
    this.photo.set(selectedFile ?? null);
  }

  async upload(request: Intervention) {
    const photo = this.photo();
    if (!photo) return;
    await this.run(async () => {
      await firstValueFrom(this.api.attach(request, photo));
      const updated = await firstValueFrom(this.api.detail(request.id));
      this.selected.set(updated);
      const draft = this.draft();
      if (draft && !draft.attempted && request.version === draft.baseVersion && updated.version === request.version + 1) {
        this.storeDraft(rebase(draft, updated.version, crypto.randomUUID()));
      }
      this.photo.set(null);
      this.notice.set('Photo ajoutée.');
      await this.refresh();
    });
  }

  async confirmSend() {
    const draft = this.draft();
    if (!draft || draft.report.trim().length < 10 || draft.report.trim().length > 2000) {
      this.error.set('Le compte rendu doit contenir entre 10 et 2 000 caractères.');
      return;
    }
    await this.run(async () => {
      const current = await firstValueFrom(this.api.detail(draft.requestId));
      if (!sendable(draft, current.version)) {
        this.conflict.set(current);
        return;
      }
      const attempted = { ...draft, attempted: true };
      const key = this.localKey('draft', draft.requestId);
      this.draft.set(attempted);
      await new Promise<void>((done, failed) => this.writes.next({ key, value: JSON.stringify(attempted), done, failed }));
      try {
        this.selected.set(await firstValueFrom(this.api.resolve(draft.requestId, draft.baseVersion, draft.report, draft.key)));
      } catch (error) {
        if ((error as { status?: number }).status === 409) {
          this.conflict.set(await firstValueFrom(this.api.detail(draft.requestId)));
          return;
        }
        throw error;
      }
      this.draft.set(null);
      this.writes.next({ key, value: null });
      this.notice.set('Compte rendu enregistré.');
      await this.refresh();
    });
  }

  useCurrentVersion() {
    const current = this.conflict();
    const draft = this.draft();
    if (!current || !draft || current.status !== 'IN_PROGRESS') return;
    this.selected.set(current);
    this.conflict.set(null);
    this.storeDraft(rebase(draft, current.version, crypto.randomUUID()));
    this.notice.set('Brouillon rattaché à la version actuelle. Confirme l’envoi quand tu es prêt.');
  }

  private async run(task: () => Promise<void>) {
    if (this.busy() || !this.online() || !this.session.accountId) return;
    this.error.set('');
    this.notice.set('');
    this.busy.set(true);
    try { await task(); } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.busy.set(false); }
  }
}

bootstrapApplication(Mobile, { providers: [provideHttpClient(), provideIonicAngular()] }).catch(console.error);

if (!Capacitor.isNativePlatform() && 'serviceWorker' in navigator) {
  void fetch('/offline-assets.json', { cache: 'no-store' }).then(response => {
    if (response.ok && response.headers.get('content-type')?.includes('json')) {
      return navigator.serviceWorker.register('/sw.js');
    }
    return undefined;
  }).catch(() => undefined);
}
