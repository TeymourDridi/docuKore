import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';

export const accountGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.load().pipe(
    map(() =>
      auth.account() ? true : router.createUrlTree(['/login'], { queryParams: { expired: 1 } })
    ),
    catchError(() => of(router.createUrlTree(['/'])))
  );
};

export const signedOutGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.load().pipe(
    map(() => {
      if (auth.account()) return router.createUrlTree(['/workspace']);
      const destination = auth.setupAvailable() ? 'setup' : 'login';
      return route.routeConfig?.path === destination
        ? true
        : router.createUrlTree(['/' + destination]);
    }),
    catchError(() => of(router.createUrlTree(['/'])))
  );
};
