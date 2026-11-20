import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, firstValueFrom, map, of, startWith, switchMap, timer } from 'rxjs';
import { ServiceOpsApi, Session, errorMessage, type AccountPage, type AccountSummary, type Role, type StaffRole } from '../../shared/api';

type DirectoryState =
  | { kind: 'loading' }
  | { kind: 'ready'; result: AccountPage }
  | { kind: 'error'; message: string };

@Component({
  selector: 'serviceops-accounts',
  imports: [ReactiveFormsModule],
  templateUrl: './accounts-admin.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountsAdmin {
  private readonly api = inject(ServiceOpsApi);
  private readonly session = inject(Session);
  private readonly revision = signal(0);
  readonly filters = signal({ query: '', page: 0 });
  readonly form = new FormGroup({
    role: new FormControl<StaffRole>('MANAGER', { nonNullable: true }),
    username: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern('[a-zA-Z0-9._-]{3,64}')] }),
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    firstName: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    lastName: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    temporaryPassword: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(12)] }),
  });
  readonly roleLabel: Record<Role, string> = {
    SUPER_ADMIN: 'Super admin', MANAGER: 'Responsable', TECHNICIAN: 'Technicien', REQUESTER: 'Demandeur',
  };
  readonly saving = signal(false);
  readonly confirmation = signal('');
  readonly formError = signal('');

  private readonly directoryRequest = computed(() => ({
    ...this.filters(), variant: this.session.variant(), revision: this.revision(),
  }));
  readonly directory = toSignal(toObservable(this.directoryRequest).pipe(
    switchMap(({ query, page }) => timer(180).pipe(
      switchMap(() => this.api.listAccounts(query.trim(), page)),
      map((result): DirectoryState => ({ kind: 'ready', result })),
      catchError(error => of<DirectoryState>({ kind: 'error', message: errorMessage(error) })),
      startWith<DirectoryState>({ kind: 'loading' }),
    )),
  ), { initialValue: { kind: 'loading' } as DirectoryState });

  fullName(account: AccountSummary) {
    return [account.firstName, account.lastName].filter(Boolean).join(' ') || account.username;
  }

  setQuery(query: string) { this.filters.set({ query, page: 0 }); }
  setPage(page: number) { this.filters.update(filters => ({ ...filters, page })); }
  reload() { this.revision.update(value => value + 1); }

  open(dialog: HTMLDialogElement) {
    this.form.reset();
    this.formError.set('');
    this.confirmation.set('');
    dialog.showModal();
  }

  async create(dialog: HTMLDialogElement) {
    if (this.saving()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.formError.set('Vérifie les champs indiqués avant de créer le compte.');
      return;
    }
    this.saving.set(true);
    this.formError.set('');
    try {
      const created = await firstValueFrom(this.api.createStaff(this.form.getRawValue()));
      this.confirmation.set(`Le compte ${created.username} a été créé.`);
      dialog.close();
      this.reload();
    } catch (error) {
      this.formError.set(errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }
}
