import { CdkDrag, CdkDropList, CdkDropListGroup, type CdkDragDrop } from '@angular/cdk/drag-drop';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import { DatePipe } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { bootstrapApplication } from '@angular/platform-browser';
import { BehaviorSubject, EMPTY, Subject, catchError, combineLatest, distinctUntilChanged, exhaustMap, expand, finalize, firstValueFrom, map, of, reduce, switchMap } from 'rxjs';
import { searchTerms } from '../../shared/search';
import { ServiceOpsApi, Session, directory, errorMessage, statusLabel, statuses, type Comment, type HistoryEntry, type Intervention, type Notification, type Page, type Priority, type Status, type TechnicianSummary } from '../../shared/api';
import { AccountsAdmin } from './accounts-admin';

@Component({
  selector: 'serviceops-root',
  imports: [FormsModule, ReactiveFormsModule, CdkDrag, CdkDropList, CdkDropListGroup, CdkTrapFocus, DatePipe, AccountsAdmin],
  templateUrl: './workbench.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Workbench {
  private readonly api = inject(ServiceOpsApi);
  private readonly destroy = inject(DestroyRef);
  readonly session = inject(Session);
  readonly directory = directory;
  readonly technicians = signal<TechnicianSummary[]>([]);
  readonly statuses = statuses;
  readonly statusLabel = statusLabel;
  readonly priorityLabel: Record<Priority, string> = { LOW: 'Basse', NORMAL: 'Normale', HIGH: 'Haute' };
  readonly adminView = signal<'accounts' | 'requests'>('accounts');
  readonly allowDemo = new URLSearchParams(location.search).has('demo');
  readonly result = signal<Page>({ items: [], total: 0, page: 0, size: 20 });
  readonly boardView = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly selected = signal<Intervention | null>(null);
  readonly assignment = signal<Intervention | null>(null);
  readonly resolution = signal<Intervention | null>(null);
  readonly comments = signal<Comment[]>([]);
  readonly history = signal<HistoryEntry[]>([]);
  readonly historyLabel: Partial<Record<string, string>> = {
    CREATE: 'Création', ASSIGN: 'Affectation', START: 'Démarrage', RESOLVE: 'Résolution',
    CLOSE: 'Clôture', ATTACH: 'Photo ajoutée', COMMENT: 'Commentaire',
  };
  readonly notifications = signal<Notification[]>([]);
  readonly notificationsOpen = signal(false);
  readonly account = computed(() => this.session.accountId);
  readonly counts = computed(() => Object.fromEntries(statuses.map(status =>
    [status, this.result().items.filter(item => item.status === status).length])));
  readonly createForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    priority: new FormControl<Priority>('NORMAL', { nonNullable: true }),
  });
  readonly report = new FormControl('', { nonNullable: true });
  readonly comment = new FormControl('', { nonNullable: true });
  readonly search = new BehaviorSubject('');
  readonly filter = new BehaviorSubject<Status | ''>('');
  readonly pageIndex = new BehaviorSubject(0);
  readonly view = new BehaviorSubject(false);
  private readonly refresh$ = new BehaviorSubject(0);
  private readonly create$ = new Subject<void>();
  private createCommand?: { fingerprint: string; key: string };

  constructor() {
    combineLatest([
      this.search.pipe(searchTerms()),
      this.filter.pipe(distinctUntilChanged()), this.pageIndex, this.view, this.refresh$,
    ]).pipe(
      switchMap(([query, status, page, board]) => {
        if (!this.account() || (this.role() === 'SUPER_ADMIN' && this.adminView() === 'accounts')) {
          return of({ items: [], total: 0, page: 0, size: 20 });
        }
        const response = board
          ? this.api.list(query, '', 0, 100).pipe(
              expand(result => (result.page + 1) * result.size < result.total
                ? this.api.list(query, '', result.page + 1, result.size) : EMPTY),
              reduce((all, result) => ({ ...result, items: [...all.items, ...result.items] }),
                { items: [] as Intervention[], total: 0, page: 0, size: 100 }),
            )
          : this.api.list(query, status, page, 20);
        return response.pipe(catchError(error => {
          this.error.set(errorMessage(error));
          return EMPTY;
        }));
      }),
      takeUntilDestroyed(this.destroy),
    ).subscribe({ next: result => this.result.set(result) });

    this.create$.pipe(exhaustMap(() => {
      const { title, priority } = this.createForm.getRawValue();
      const cleaned = title.trim();
      if (cleaned.length < 5 || cleaned.length > 120) {
        this.error.set('Le titre doit contenir entre 5 et 120 caractères.');
        return EMPTY;
      }
      const fingerprint = JSON.stringify([cleaned, priority]);
      if (this.createCommand?.fingerprint !== fingerprint) {
        this.createCommand = { fingerprint, key: crypto.randomUUID() };
      }
      this.busy.set(true);
      this.error.set('');
      return this.api.create(cleaned, priority, this.createCommand.key).pipe(
        catchError(error => { this.error.set(errorMessage(error)); return EMPTY; }),
        finalize(() => this.busy.set(false)),
      );
    }), takeUntilDestroyed(this.destroy)).subscribe(() => {
      this.createCommand = undefined;
      this.createForm.reset();
      this.refresh();
    });

    this.session.initialize('serviceops-web').then(() => this.refresh())
      .catch(() => this.error.set('Connexion OIDC impossible. Vérifie que Keycloak est démarré.'));
  }

  login() { return this.run(() => this.session.login()); }
  register() { return this.run(() => this.session.login(true)); }

  useDemo(id: string) { this.session.useDemo(id); this.refresh(); }
  create() { this.create$.next(); }
  role() { return this.session.role; }
  setAdminView(view: 'accounts' | 'requests') {
    this.clearDetail();
    this.adminView.set(view);
    this.refresh();
  }
  setView(board: boolean) { this.boardView.set(board); this.view.next(board); }
  setQuery(query: string) { this.pageIndex.next(0); this.search.next(query); }
  setStatus(status: Status | '') { this.pageIndex.next(0); this.filter.next(status); }
  refresh() { this.refresh$.next(this.refresh$.value + 1); }
  items(status: Status) { return this.result().items.filter(item => item.status === status); }

  async changeBackend(variant: 'java' | 'kotlin') {
    this.session.selectVariant(variant);
    this.clearDetail();
    this.result.set({ items: [], total: 0, page: 0, size: 20 });
    this.refresh();
  }

  clearDetail() {
    this.selected.set(null);
    this.comments.set([]);
    this.history.set([]);
    this.notifications.set([]);
    this.notificationsOpen.set(false);
    this.assignment.set(null);
    this.resolution.set(null);
  }

  async logout() {
    this.clearDetail();
    this.adminView.set('accounts');
    await this.run(() => this.session.logout());
    this.refresh();
  }

  async detail(id: string) {
    await this.run(() => this.loadDetail(id));
  }

  private async loadDetail(id: string) {
    const [request, comments, history] = await Promise.all([
      firstValueFrom(this.api.detail(id)), firstValueFrom(this.api.comments(id)), firstValueFrom(this.api.history(id)),
    ]);
    this.selected.set(request);
    this.comments.set(comments);
    this.history.set(history);
  }

  async sendComment(request: Intervention) {
    const value = this.comment.value.trim();
    if (!value) return;
    await this.run(async () => {
      await firstValueFrom(this.api.comment(request, value));
      this.comment.reset();
      await this.loadDetail(request.id);
      this.refresh();
    });
  }

  async openAssignment(request: Intervention) {
    this.technicians.set([]);
    this.assignment.set(request);
    await this.run(async () => {
      this.technicians.set(await firstValueFrom(this.api.technicians()));
    });
  }
  openResolution(request: Intervention) { this.report.setValue(''); this.resolution.set(request); }

  closeRequestDialog() {
    if (this.busy()) return;
    this.assignment.set(null);
    this.resolution.set(null);
    this.error.set('');
  }

  async confirmAssignment(request: Intervention, target: string) {
    if (!target) return;
    await this.run(async () => {
      await firstValueFrom(this.api.assign(request, target));
      this.assignment.set(null);
      this.refresh();
      if (this.selected()?.id === request.id) await this.loadDetail(request.id);
    });
  }

  async transit(request: Intervention, action: 'start' | 'close') {
    await this.run(async () => {
      await firstValueFrom(this.api.transition(request, action));
      this.refresh();
      if (this.selected()?.id === request.id) await this.loadDetail(request.id);
    });
  }

  async confirmResolution(request: Intervention) {
    const report = this.report.value.trim();
    if (report.length < 10 || report.length > 2000) {
      this.error.set('Le compte rendu doit contenir entre 10 et 2 000 caractères.');
      return;
    }
    await this.run(async () => {
      await firstValueFrom(this.api.resolve(request.id, request.version, report, crypto.randomUUID()));
      this.resolution.set(null);
      this.refresh();
      if (this.selected()?.id === request.id) await this.loadDetail(request.id);
    });
  }

  async drop(event: CdkDragDrop<Status, Status, Intervention>) {
    const request = event.item.data;
    const target = event.container.data;
    if (target === request.status && !(target === 'ASSIGNED' && this.role() === 'MANAGER')) return;
    if (target === 'ASSIGNED' && this.role() === 'MANAGER' &&
        (request.status === 'OPEN' || request.status === 'ASSIGNED')) this.openAssignment(request);
    else if (target === 'IN_PROGRESS' && this.role() === 'TECHNICIAN' && request.status === 'ASSIGNED') await this.transit(request, 'start');
    else if (target === 'RESOLVED' && this.role() === 'TECHNICIAN' && request.status === 'IN_PROGRESS') this.openResolution(request);
    else if (target === 'CLOSED' && request.status === 'RESOLVED' && this.role() !== 'TECHNICIAN') await this.transit(request, 'close');
    else this.error.set('Cette transition est indisponible pour ton rôle ou pour ce statut.');
  }

  async showNotifications() {
    await this.run(async () => {
      this.notifications.set(await firstValueFrom(this.api.notifications()));
      this.notificationsOpen.set(true);
    });
  }

  private async run(task: () => Promise<void>) {
    if (this.busy()) return;
    this.error.set('');
    this.busy.set(true);
    try { await task(); } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.busy.set(false); }
  }
}

bootstrapApplication(Workbench, { providers: [provideHttpClient()] }).catch(console.error);
