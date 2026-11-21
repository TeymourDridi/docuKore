import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap, tap, throwError } from 'rxjs';

export interface Account {
  readonly id: string;
  readonly username: string;
  readonly fullName: string | null;
  readonly email: string | null;
}
export interface SetupStatus {
  readonly available: boolean;
  readonly deploymentMode: 'LOCAL' | 'HOSTED';
}
export interface SignupRequest {
  readonly username: string | null;
  readonly email: string | null;
  readonly fullName: string;
  readonly cabinetName: string;
  readonly password: string;
  readonly idempotencyKey: string;
}
export interface RegistrationResult {
  readonly registrationId: string;
  readonly cabinetId: string | null;
  readonly status:
    'PENDING_EMAIL_VERIFICATION' | 'DELIVERY_FAILED' | 'PROVISIONING_PENDING' | 'ACTIVE';
}
export interface SetupRequest {
  readonly username: string;
  readonly fullName: string;
  readonly password: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly current = signal<Account | null>(null);
  private readonly available = signal(false);
  readonly account = this.current.asReadonly();
  readonly setupAvailable = this.available.asReadonly();

  refreshCsrf(): Observable<void> {
    return this.http.get<void>('/api/auth/csrf');
  }

  load(): Observable<void> {
    return this.refreshCsrf().pipe(
      switchMap(() =>
        forkJoin({
          account: this.http.get<Account>('/api/auth/me').pipe(
            catchError((error: unknown) => {
              if (error instanceof HttpErrorResponse && error.status === 401) return of(null);
              return throwError(() => error);
            })
          ),
          setup: this.http.get<SetupStatus>('/api/auth/setup'),
        })
      ),
      tap((result) => {
        this.current.set(result.account);
        this.available.set(result.setup.available);
      }),
      map(() => undefined)
    );
  }

  registrationSettings(): Observable<SetupStatus> {
    return this.refreshCsrf().pipe(switchMap(() => this.http.get<SetupStatus>('/api/auth/setup')));
  }

  signup(request: SignupRequest): Observable<RegistrationResult> {
    return this.refreshCsrf().pipe(
      switchMap(() => this.http.post<RegistrationResult>('/api/auth/signup', request))
    );
  }

  verifyEmail(token: string): Observable<RegistrationResult> {
    return this.refreshCsrf().pipe(
      switchMap(() => this.http.post<RegistrationResult>('/api/auth/verify-email', { token }))
    );
  }

  resendVerification(email: string): Observable<{ status: 'ACCEPTED' }> {
    return this.refreshCsrf().pipe(
      switchMap(() =>
        this.http.post<{ status: 'ACCEPTED' }>('/api/auth/resend-verification', { email })
      )
    );
  }

  setup(request: SetupRequest): Observable<void> {
    return this.http
      .post<void>('/api/auth/setup', request)
      .pipe(tap(() => this.available.set(false)));
  }

  login(username: string, password: string): Observable<Account> {
    const body = new HttpParams().set('username', username).set('password', password);
    return this.http.post<{ authenticated: boolean }>('/api/auth/login', body).pipe(
      switchMap(() => this.refreshCsrf()),
      switchMap(() => this.http.get<Account>('/api/auth/me')),
      tap((account) => this.current.set(account))
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {}).pipe(
      catchError((error: unknown) =>
        error instanceof HttpErrorResponse && error.status === 401
          ? of(undefined)
          : throwError(() => error)
      ),
      tap(() => this.clear()),
      switchMap(() => this.refreshCsrf())
    );
  }

  clear(): void {
    this.current.set(null);
  }
}
