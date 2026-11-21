import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const sessionInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        auth.account() &&
        request.url !== '/api/auth/login'
      ) {
        auth.clear();
        void router.navigate(['/login'], { queryParams: { expired: 1 } });
      }
      if (
        error instanceof HttpErrorResponse &&
        error.status === 403 &&
        request.url !== '/api/auth/csrf'
      ) {
        // Refresh for a deliberate user retry; never replay unsafe requests automatically.
        auth.refreshCsrf().subscribe({ error: () => undefined });
      }
      return throwError(() => error);
    })
  );
};
