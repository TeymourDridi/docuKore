import { HttpErrorResponse } from '@angular/common/http';
import { TranslationKey } from '../i18n/translations';

export function registrationError(error: unknown): TranslationKey {
  if (!(error instanceof HttpErrorResponse)) return 'registrationUnavailable';
  if (error.status === 0) return 'registrationNetworkError';
  if (error.status === 400) return 'registrationInvalidInput';
  if (error.status === 409) return 'registrationConflict';
  if (error.status === 403) return 'csrfError';
  return 'registrationUnavailable';
}
