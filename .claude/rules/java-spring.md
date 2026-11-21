---
paths:
  - "backend/**/*.java"
---

# Java and Spring Boot Engineering Standards

## 1. Dependency Injection — Mandatory

Use constructor injection for all mandatory dependencies.

- Use explicit constructors.
- Declare injected dependencies as private final fields.
- Assign dependencies through constructor parameters.
- Never use field injection.
- Never use @Autowired.
- Never use @Inject or @Resource for field injection.
- Do not use Lombok-generated constructors.
- Do not introduce Lombok merely to reduce constructor boilerplate.

A Spring-managed component with one constructor does not need @Autowired.

Example:

@Service
public class PatientService {

    private final PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }
}

Do not write:

@Service
public class PatientService {

    @Autowired
    private PatientRepository patientRepository;
}

## 2. Constructor Design

- Mandatory dependencies belong in the constructor.
- Avoid unnecessary setter injection.
- Prefer one clear constructor for Spring-managed components.
- Use @Bean factory methods when explicit object construction is appropriate.
- Use @Qualifier on constructor parameters when bean selection needs clarification.
- Do not use ApplicationContext.getBean() as a replacement for dependency injection.
- Do not introduce service locator patterns.
- Investigate excessive constructor dependencies as a possible sign of poor class responsibility separation.

If circular dependencies occur, reconsider the design rather than hiding the cycle with @Lazy or setter injection.

## 3. Java Code Consistency

- Prefer clear, explicit and readable code.
- Use Java records for suitable immutable DTOs.
- Use private final fields for mandatory dependencies.
- Avoid mutable shared state.
- Follow consistent naming conventions.
- Keep classes focused on one coherent responsibility.
- Avoid unnecessary interfaces, generic abstractions and utility classes.
- Do not expose persistence entities directly through REST APIs.

### Java Indentation — Mandatory

- Treat `backend/checkstyle.xml` as the source of truth; do not change or suppress checks to accommodate formatting.
- Use spaces, not tabs, with four spaces per block level. Braces remain aligned with their declaration.
- Wrapped method-call arguments must be indented at least four spaces beyond the call's indentation at its actual
  nesting level. Do not use a fixed continuation column that ignores enclosing lambdas or nested blocks.
- For annotation arrays whose first element follows `{` on the same line, align wrapped elements with that first
  element. Do not apply an arbitrary extra four or eight spaces to the annotation's indentation.
- If aligning an annotation array would exceed the 120-character limit, move all elements below the opening `{`
  and use the configured four-space `arrayInitIndent`; keep the array indentation consistent instead of outdenting
  only the longest element.
- Preserve nearby valid formatting and avoid whole-file reformatting for a local fix.
- After a coherent Java implementation batch, run `./mvnw checkstyle:check` from `backend`, saving output under
  `backend/target`. Resolve new violations before declaring that batch complete; do not rerun tests solely for
  whitespace-only changes. Report pre-existing violations separately without silently expanding scope.

Annotation array alignment example:

```java
    @ValueSource(strings = {"first", "second",
                            "third", "fourth"})
```

### Java Line Length — Mandatory

- Keep Java source lines at or below **120 characters**, including indentation and trailing comments.
- Apply this to production code, tests and Javadocs. Existing exclusions in `backend/checkstyle.xml` remain unchanged;
  never expand exclusions, add suppressions or increase the limit to accommodate new code.
- Wrap record components, constructor/method arguments, annotation arrays and chained expressions at readable
  syntactic boundaries, preserving the mandatory indentation rules above.
- Prefer wrapping arguments over splitting a string. When a string itself must be split, preserve its exact value,
  including spaces, placeholders and escape sequences; do not introduce text blocks or change messages for formatting.
- Check line lengths while editing so added indentation or documentation does not introduce fresh violations.
- Use the single Checkstyle audit after a coherent Java batch described above to verify both length and indentation;
  do not add extra builds or test runs solely for line wrapping.

### Java Type Javadoc — Mandatory

- Follow `JavadocType` in `backend/checkstyle.xml` for production and test sources; never weaken or suppress checks.
- Its default requires `@param` tags for every documented record component and generic type parameter.
- Give each documented record component a meaningful `@param componentName` description matching its exact name;
  keep tags synchronized when components are renamed, added or removed. Document generic parameters as `@param <T>`.
- Place type Javadoc before annotations so it documents the declaration correctly.
- Never include credential values in Javadoc.

### Java Brace Placement — Mandatory

- Follow `LeftCurly` option `nl` and the default `RightCurly` option `same` in `backend/checkstyle.xml`, in both
  production and test sources; never weaken or suppress checks. This combination is **not pure Allman**.
- Put opening block braces on the next line, aligned with their declaration.
- Keep continuations on the closing brace's line: `} else`, `} else if (...)`, `} catch (...)`, `} finally`
  and `} while (...)`. Any following opening block brace still goes on the next line.
- Use the single Checkstyle audit already described above for these Javadoc and brace rules too; do not add
  duplicate verification commands.

```java
if (condition)
{
    performAction();
} else
{
    performAlternative();
}
```

## 4. Spring Conventions

- Keep controllers thin.
- Keep application orchestration out of controllers.
- Use explicit transactional boundaries.
- Prefer type-safe configuration properties over field-based @Value injection.
- Let Spring Boot manage compatible dependency versions.
- Do not introduce circular dependencies.
- Do not bypass the established architectural boundaries.

## 5. Testing

- Unit tests should instantiate ordinary classes through their constructors.
- Prefer explicit test dependencies.
- Avoid field injection in tests.
- Use Spring integration tests when actual container wiring needs verification.
- Do not mock framework behavior that should be verified through a real integration test.

## 6. Exceptions

Some framework-managed classes, such as JPA entities, may require a protected no-argument constructor.

This is a persistence-framework requirement, not permission to use field injection.

Any genuine exception to these conventions must be justified rather than silently introducing another coding style.

## 7. Safe Diagnostic Logging

- Use the `docukore-diagnostics` skill when logs can clarify a failure or incomplete workflow. Diagnosis is read-only
  by default; it does not authorize launches, requests, edits, log capture or tests.
- The request-correlation contract is a backend-generated UUID returned in the response `X-Request-ID` header and
  stored under MDC `requestId`. Never adopt a client-supplied header value. This opaque value is for correlation only,
  never identity, authorization, tenant resolution or idempotency.
- Scope MDC changes to the owning request boundary: save the previous `requestId` value and restore it in `finally`,
  or remove only that key if previously absent. Never clear the whole MDC or overwrite unrelated keys; do not let
  request correlation leak between reused threads. Do not assume MDC follows asynchronous work automatically.
- Use class-local loggers and stable, fixed event codes. Apart from `requestId` and normal timestamp/level/class
  metadata, diagnostic fields are limited to fixed operation/outcome/phase/failure-code categories, allowlisted HTTP
  methods, servlet dispatch types, bounded HTTP status (or fixed UNKNOWN/UNRESOLVED), elapsed duration and an allowlisted
  deployment category. Never emit a raw method/path or arbitrary exception code; map unknown values to fixed fallbacks.
  Do not build event codes or categories from user input, data or configuration dumps.
- Never log names, usernames/emails, medical data, business/user/account/tenant identifiers, schema names, entities,
  request DTOs or other request/response payloads, headers/cookies, tokens/passwords, proof links, idempotency keys,
  raw paths/query strings, SQL/parameters, exception messages/stacks, provider details or configuration values.
  The server-generated correlation UUID is the sole permitted diagnostic identifier; do not log the full header.
- Map failures to fixed safe events/outcomes rather than logging exception text or passing a throwable to a logger.
  Keep diagnostic arguments allowlisted; do not rely on generic redaction or object `toString()` to make them safe.
- INFO is for meaningful business transitions/outcomes, DEBUG for intermediate phases, WARN/ERROR for actionable
  failures. Avoid noisy per-method logs, duplicate failure logs, blanket DEBUG and SQL/bind-parameter logging.
  A success event must reflect the actual completed business step; HTTP 200 alone may still represent pending work.
- Temporary DEBUG must target only the relevant class through explicitly approved child-process arguments supported
  by the actual entry point. Do not invent launcher argument support: current backend/test launchers expose no
  arbitrary child-argument option. Never implicitly edit launchers, persistent environment variables, protected
  configuration, hooks or global settings to enable diagnostics.
- Test logging changes with fictional/synthetic data and a scoped class-local log capture: verify fixed event/level
  and allowed fields, absence of sensitive canary values and exception text/stacks, and requestId isolation/restoration
  on both normal and failure paths, including pre-existing and absent MDC values and preservation of unrelated keys.
  Do not weaken business/security assertions or introduce real secrets/medical data into tests or artifacts.
- Inspect artifacts narrowly; test-runner masking of three database values is not general secret sanitization and
  Surefire artifacts may be sensitive. Use one verification owner and focused tests after a coherent authorized batch.
