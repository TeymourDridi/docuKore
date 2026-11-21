import { Routes } from '@angular/router';
import { accountGuard, signedOutGuard } from './auth/auth.guards';

export const routes: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./auth/entry').then((m) => m.Entry) },
  {
    path: 'setup',
    canActivate: [signedOutGuard],
    loadComponent: () => import('./auth/setup').then((m) => m.Setup),
  },
  {
    path: 'login',
    canActivate: [signedOutGuard],
    loadComponent: () => import('./auth/login').then((m) => m.Login),
  },
  {
    path: 'register',
    loadComponent: () => import('./auth/register').then((m) => m.Register),
  },
  {
    path: 'verify-email',
    loadComponent: () => import('./auth/verify-email').then((m) => m.VerifyEmail),
  },
  {
    path: 'workspace',
    canActivate: [accountGuard],
    loadComponent: () => import('./auth/workspace').then((m) => m.Workspace),
  },
  { path: '**', redirectTo: '' },
];
