import { HttpErrorResponse } from '@angular/common/http';
import { registrationError } from './registration-errors';

describe('Registration errors', () => {
  it.each([
    [0, 'registrationNetworkError'],
    [400, 'registrationInvalidInput'],
    [403, 'csrfError'],
    [409, 'registrationConflict'],
    [503, 'registrationUnavailable'],
    [500, 'registrationUnavailable'],
  ] as const)('maps status %s without exposing raw server information', (status, key) => {
    const error = new HttpErrorResponse({
      status,
      error: { code: 'INTERNAL', message: 'private internal detail' },
    });
    expect(registrationError(error)).toBe(key);
  });
  it('handles an unknown failure without displaying its contents', () => {
    expect(registrationError(new Error('private detail'))).toBe('registrationUnavailable');
  });
});
