import { Routes } from '@angular/router';

export const WORKSPACE_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'patients' },
  {
    path: 'patients',
    loadChildren: () =>
      import('../../features/patients/patients.routes').then((m) => m.PATIENTS_ROUTES),
  },
];
