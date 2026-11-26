import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, catchError, map, of, switchMap, timer } from 'rxjs';
import { LanguageService } from '../../../i18n/language.service';
import { PatientApi } from '../patient-api';
import { PatientListItem, PatientListQuery, PatientPage } from '../patient.model';

type ListRequest = {
  readonly query: PatientListQuery;
  readonly debounce: boolean;
  readonly redirected?: boolean;
};

@Component({
  selector: 'app-patient-list',
  templateUrl: './patient-list.html',
  styleUrl: './patient-list.scss',
})
export class PatientList {
  protected readonly i18n = inject(LanguageService);

  private readonly patientApi = inject(PatientApi);
  private readonly requests = new Subject<ListRequest>();

  protected readonly searchText = signal('');
  protected readonly patients = signal<readonly PatientListItem[]>([]);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly loading = signal(true);
  protected readonly hasError = signal(false);
  protected readonly hasSearch = computed(() => this.searchText().trim().length > 0);
  protected readonly canGoPrevious = computed(() => !this.loading() && this.page() > 0);
  protected readonly canGoNext = computed(
    () => !this.loading() && this.page() + 1 < this.totalPages()
  );

  constructor() {
    this.requests
      .pipe(
        switchMap((request) => {
          const delay = request.debounce ? timer(300) : of(0);
          return delay.pipe(
            switchMap(() =>
              this.patientApi.listPatients(request.query).pipe(
                map((response) => ({ request, response })),
                catchError(() => {
                  this.hasError.set(true);
                  this.loading.set(false);
                  return EMPTY;
                })
              )
            )
          );
        }),
        takeUntilDestroyed()
      )
      .subscribe(({ request, response }) => this.showResponse(request, response));

    this.load({}, false);
  }

  protected updateSearch(event: Event): void {
    this.setSearchText((event.target as HTMLInputElement).value);
  }

  protected clearSearch(): void {
    this.setSearchText('');
  }

  protected previousPage(): void {
    if (!this.canGoPrevious()) return;
    this.loadPage(this.page() - 1);
  }

  protected nextPage(): void {
    if (!this.canGoNext()) return;
    this.loadPage(this.page() + 1);
  }

  protected retry(): void {
    const search = this.searchText().trim();
    const currentPage = this.page();
    this.load(
      {
        ...(currentPage > 0 ? { page: currentPage } : {}),
        ...(search ? { search } : {}),
      },
      false
    );
  }

  private setSearchText(value: string): void {
    const previousSearch = this.searchText().trim();
    this.searchText.set(value);
    const search = value.trim();
    if (search === previousSearch) return;

    this.page.set(0);
    this.load(search ? { search } : {}, true);
  }

  private loadPage(page: number): void {
    this.page.set(page);
    const search = this.searchText().trim();
    this.load({ page, ...(search ? { search } : {}) }, false);
  }

  private load(query: PatientListQuery, debounce: boolean, redirected = false): void {
    this.loading.set(true);
    this.hasError.set(false);
    this.patients.set([]);
    this.totalPages.set(0);
    this.requests.next({ query, debounce, redirected });
  }

  private showResponse(request: ListRequest, response: PatientPage): void {
    this.totalPages.set(response.totalPages);
    if (response.totalPages === 0) {
      // The directory (or search) is genuinely empty; no redirect survives an empty result.
      this.page.set(0);
      this.patients.set([]);
      this.loading.set(false);
      return;
    }
    if (response.page >= response.totalPages) {
      // The requested page no longer exists, but valid pages remain: show the last one.
      if (!request.redirected) {
        this.redirectToLastPage(request, response);
        return;
      }
      // Guard second out-of-range answer: settle on empty state instead of retrying forever.
      this.page.set(response.totalPages - 1);
      this.patients.set([]);
      this.loading.set(false);
      return;
    }
    this.page.set(response.page);
    this.patients.set(response.content);
    this.loading.set(false);
  }

  private redirectToLastPage(request: ListRequest, response: PatientPage): void {
    const lastPage = response.totalPages - 1;
    this.page.set(lastPage);
    this.load({ ...request.query, page: lastPage }, false, true);
  }
}
