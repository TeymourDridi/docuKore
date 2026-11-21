import { HttpErrorResponse } from '@angular/common/http';
import { TranslationKey } from '../i18n/translations';

export function authError(error: unknown, login = false): TranslationKey {
  if (!(error instanceof HttpErrorResponse)) return 'unavailable';
  if (error.status === 401 && login) return 'invalidCredentials';
  if (error.status === 400) return 'invalidInput';
  if (error.status === 404 || error.status === 409) return 'setupUnavailable';
  if (error.status === 403) return 'csrfError';
  return 'unavailable';
}
