---
paths:
  - "frontend/**/*.ts"
  - "frontend/**/*.html"
  - "frontend/**/*.scss"
  - "frontend/**/*.css"
---

# Angular Engineering Standards

## 1. Architecture

- Use standalone components for new development.
- Organize code by business feature.
- Keep App focused on the root router outlet; place shells in `layout/public-shell` and `layout/workspace-shell`, and the patient list in `features/patients/patient-list`.
- Use lazy-loaded feature routes defined separately from components.
- Keep clear separation between presentation, application logic and data access; colocate API logic with its business feature.
- Do not introduce generic `pages`, `components`, `services` or `data-access` directories, or premature `shared` abstractions.
- Avoid unnecessary NgModules in new architecture.
- Avoid premature abstractions and excessive component fragmentation.
- Keep the application architecture consistent.

### Naming — Task 9.5.5.1 agreement

- For new Angular 22 components, use suffixless kebab-case filenames and matching PascalCase classes: `patient-list.ts` → `PatientList`, `workspace-shell.ts` → `WorkspaceShell`.
- Do not add `.component.ts` or `Component` suffixes. Keep one main concept per file and prefer descriptive names over `utils`, `helpers` or `common`.
- Keep component TypeScript, HTML, SCSS and unit-test files on the same basename: `patient-list.ts`, `patient-list.html`, `patient-list.scss`, `patient-list.spec.ts`. Unit tests use `.spec.ts`.
- Use purposeful names for other feature files: `patient-api.ts` → `PatientApi`, `patient.model.ts`, `patients.routes.ts` → `PATIENTS_ROUTES`.
- Do not rename legacy auth files or classes as part of this convention update.

## 2. Dependency Injection

- Prefer Angular's inject() function for dependency injection.
- Use readonly fields for injected services where appropriate.
- Avoid manually creating service instances.
- Avoid service locator patterns.
- Do not mix constructor parameter injection and inject() without a justified reason.

Example:

```typescript
@Component({
  selector: 'app-patient-list',
  templateUrl: './patient-list.html',
  styleUrl: './patient-list.scss'
})
export class PatientList {

  private readonly patientApi = inject(PatientApi);

}
```

Follow the official Angular style guide.

## 3. TypeScript

- Maintain strict TypeScript settings.
- Avoid any.
- Prefer unknown for values whose types are not yet established.
- Define explicit API contracts and domain types.
- Avoid unnecessary type assertions.
- Prefer readonly properties and immutable data where appropriate.
- Use consistent naming conventions.
- Keep types close to their relevant feature unless shared intentionally.

## 4. Components

- Components must have focused responsibilities.
- Keep presentation components separate from data-access responsibilities.
- Avoid complex business logic inside templates.
- Use computed values for derived state.
- Keep lifecycle methods short and readable.
- Do not access the DOM directly unless genuinely necessary.
- Avoid unnecessary component inheritance.

Use Angular's built-in template control flow for new development where appropriate.

## 5. State Management

- Prefer Signals for appropriate local synchronous state.
- Use computed() for derived state.
- Avoid effects for ordinary state propagation.
- Use RxJS for asynchronous streams and complex observable workflows.
- Use Angular's RxJS interoperability APIs where appropriate.
- Avoid duplicated sources of truth.
- Do not introduce NgRx or another global state library without a demonstrated need.

## 6. Forms

- Use typed Reactive Forms as the project default for data-entry workflows.
- Do not introduce Signal Forms or another form framework as part of this milestone.
- Keep validation understandable and reusable where appropriate.
- Validate important data on both frontend and backend.
- Never treat frontend validation as a security boundary.

## 7. HTTP and API Communication

- Centralize HTTP communication in dedicated feature API classes such as `PatientApi`, colocated with their feature.
- Do not place business API calls directly in presentation components.
- Use typed request and response models.
- Use functional HTTP interceptors.
- Handle loading, success and error states consistently.
- Avoid duplicate HTTP requests where appropriate.
- Do not expose sensitive information in error messages or browser logs.

## 8. Routing and Authorization

- Use lazy-loaded feature routes.
- Prefer functional route guards and resolvers.
- Keep routing responsibilities separate from business logic.
- Guards may improve navigation and user experience.
- NEVER rely on Angular guards to secure backend resources.
- All permissions, subscriptions and tenant access must be verified by the backend.

## 9. Performance and Change Detection

- Follow OnPush-compatible component design.
- Use the appropriate change-detection defaults for the selected Angular version.
- Avoid unnecessary recomputation.
- Use track expressions in list rendering.
- Avoid unnecessary subscriptions and duplicated state.
- Use takeUntilDestroyed() where explicit subscription cleanup is necessary.
- Prefer async pipe or suitable Signal/RxJS interoperation over unmanaged subscriptions.

## 10. Accessibility and UI Quality

- Use semantic HTML.
- Provide accessible labels for form controls.
- Support keyboard navigation.
- Provide visible focus states.
- Use appropriate contrast.
- Never rely exclusively on color to communicate important medical alerts.
- Keep responsive behavior consistent.
- Avoid unsafe HTML rendering.

## 11. Privacy and Sensitive Data

- Do not store medical information unnecessarily in browser storage.
- Do not put secrets in frontend environment files.
- Follow the approved authentication and session architecture.
- Never assume that hiding a UI element provides authorization.
- Avoid exposing patient information in browser console logs.
- Respect the active tenant context.

## 12. Testing and Consistency

- Follow the testing framework selected for the project.
- Test components, services and important user interactions.
- Include loading, empty, success and failure states.
- Test authorization-dependent UI behavior.
- Avoid brittle implementation-detail tests.
- Follow established project patterns.
- Do not introduce another state, routing or form library without architectural justification.
- Verify official documentation for version-specific APIs before implementation.
