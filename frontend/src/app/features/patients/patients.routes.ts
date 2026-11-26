import { Routes } from '@angular/router';

export const PATIENTS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./patient-list/patient-list').then((m) => m.PatientList),
  },
];
