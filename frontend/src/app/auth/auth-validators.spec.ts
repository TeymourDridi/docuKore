import { FormControl, FormGroup } from '@angular/forms';
import {
  emailAddress,
  passwordConfirmation,
  passwordPolicy,
  requiredName,
  requiredEmailAddress,
} from './auth-validators';

describe('Registration validation', () => {
  it('matches the backend Unicode password length and byte limits', () => {
    expect(passwordPolicy(new FormControl('a'.repeat(11)))).not.toBeNull();
    expect(passwordPolicy(new FormControl('a'.repeat(12)))).toBeNull();
    expect(passwordPolicy(new FormControl('a'.repeat(72)))).toBeNull();
    expect(passwordPolicy(new FormControl('a'.repeat(73)))).not.toBeNull();
    expect(passwordPolicy(new FormControl('😀'.repeat(12)))).toBeNull();
    expect(passwordPolicy(new FormControl('😀'.repeat(19)))).not.toBeNull();
  });
  it('normalizes names before applying backend limits and rejects blank/control characters', () => {
    expect(requiredName(new FormControl(' \t '))).not.toBeNull();
    expect(requiredName(new FormControl('Dr. Fictional'))).toBeNull();
    expect(requiredName(new FormControl('\u001cAlice\u001f'))).toBeNull();
    expect(requiredName(new FormControl(' '))).toBeNull();
    expect(requiredName(new FormControl('é'.repeat(255)))).toBeNull();
    expect(requiredName(new FormControl('a'.repeat(256)))).not.toBeNull();
    expect(requiredName(new FormControl('Dr.\u0085Fictional'))).not.toBeNull();
  });
  it('accepts an omitted local email and validates supplied email against the backend format', () => {
    expect(emailAddress(new FormControl(''))).toBeNull();
    expect(emailAddress(new FormControl(' Doctor+tag@Example.test '))).toBeNull();
    expect(emailAddress(new FormControl('doctor@localhost'))).not.toBeNull();
    expect(emailAddress(new FormControl('doctor example@test.example'))).not.toBeNull();
    expect(emailAddress(new FormControl('a'.repeat(256) + '@example.test'))).not.toBeNull();
  });
  it('requires a nonblank hosted email after backend whitespace normalization', () => {
    expect(requiredEmailAddress(new FormControl(' '))).toEqual({ required: true });
    expect(requiredEmailAddress(new FormControl('doctor@example.test'))).toBeNull();
  });
  it('rejects differing password confirmation', () => {
    const group = new FormGroup({
      password: new FormControl('fictional-password'),
      confirmation: new FormControl('different'),
    });
    expect(passwordConfirmation(group)).toEqual({ passwordConfirmation: true });
    group.controls.confirmation.setValue('fictional-password');
    expect(passwordConfirmation(group)).toBeNull();
  });
});
