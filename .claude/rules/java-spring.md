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
