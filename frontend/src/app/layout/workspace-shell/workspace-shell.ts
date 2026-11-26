import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { authError } from '../../auth/auth-errors';
import { LanguageService } from '../../i18n/language.service';
import { TranslationKey } from '../../i18n/translations';

@Component({
  selector: 'app-workspace-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './workspace-shell.html',
  styleUrl: './workspace-shell.scss',
})
export class WorkspaceShell {
  protected readonly i18n = inject(LanguageService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly accountName = computed(
    () =>
      this.auth.account()?.fullName || this.auth.account()?.username || this.i18n.text('account')
  );
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);

  protected logout(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.auth
      .logout()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false))
      )
      .subscribe({
        next: () => {
          void this.router.navigateByUrl('/login');
        },
        error: (error: unknown) => {
          this.error.set(authError(error));
        },
      });
  }
}
