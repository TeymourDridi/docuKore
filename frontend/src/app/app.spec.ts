import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('provides only the root router outlet, leaving page framing to routed shells', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.children).toHaveLength(1);
    expect(page.firstElementChild?.tagName.toLowerCase()).toBe('router-outlet');
    expect(
      page.querySelector('header, footer, select, app-public-shell, app-workspace-shell')
    ).toBeNull();
    expect(page.textContent?.trim()).toBe('');
  });
});
