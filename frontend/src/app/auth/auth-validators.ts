import { ValidatorFn } from '@angular/forms';

export const passwordPolicy: ValidatorFn = (control) => {
  const value: unknown = control.value;
  return typeof value === 'string' &&
    Array.from(value).length >= 12 &&
    new TextEncoder().encode(value).length <= 72
    ? null
    : { passwordPolicy: true };
};

// Java strip() uses Character.isWhitespace, excluding nonbreaking spaces and NEL.
export function stripRegistrationWhitespace(value: string): string {
  const whitespace =
    '[\\u0009-\\u000d\\u001c-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200a\\u2028\\u2029\\u205f\\u3000]';
  return value.replace(new RegExp(`^${whitespace}+|${whitespace}+$`, 'gu'), '');
}

export const requiredName: ValidatorFn = (control) => {
  const value: unknown = control.value;
  const normalized =
    typeof value === 'string' ? stripRegistrationWhitespace(value).normalize('NFC') : '';
  return normalized.length > 0 &&
    normalized.length <= 255 &&
    !/[\u0000-\u001f\u007f-\u009f]/u.test(normalized)
    ? null
    : { requiredName: true };
};

export const emailAddress: ValidatorFn = (control) => {
  const value: unknown = control.value;
  if (typeof value !== 'string') return { email: true };
  const normalized = stripRegistrationWhitespace(value).toLowerCase();
  if (!normalized) return null;
  return normalized.length <= 255 &&
    /^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}$/.test(normalized)
    ? null
    : { email: true };
};

export const requiredEmailAddress: ValidatorFn = (control) => {
  const value: unknown = control.value;
  return typeof value === 'string' && stripRegistrationWhitespace(value).length > 0
    ? emailAddress(control)
    : { required: true };
};

export const passwordConfirmation: ValidatorFn = (group) =>
  group.get('password')?.value === group.get('confirmation')?.value
    ? null
    : { passwordConfirmation: true };
