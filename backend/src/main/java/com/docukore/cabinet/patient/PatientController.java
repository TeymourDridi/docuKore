package com.docukore.cabinet.patient;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Patient HTTP boundary; cabinet selection and authorization belong to the application services. */
@RestController
@RequestMapping("/api/patients")
public class PatientController
{
    /** Application service responsible for authorized patient creation. */
    private final PatientCreationService creation;

    /** Application service responsible for authorized directory reads. */
    private final PatientListingService listing;

    /** Application service responsible for authorized persisted detail reads. */
    private final PatientDetailService detail;

    /** Application service responsible for authorized partial updates. */
    private final PatientUpdateService update;

    public PatientController(PatientCreationService creation, PatientListingService listing,
                             PatientDetailService detail, PatientUpdateService update)
    {
        this.creation = creation;
        this.listing = listing;
        this.detail = detail;
        this.update = update;
    }

    @GetMapping
    ResponseEntity<PatientPageResponse> list(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String search)
    {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(listing.list(page, size, search));
    }

    @GetMapping("/{patientId}")
    ResponseEntity<PatientDetailResponse> detail(@PathVariable UUID patientId)
    {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(detail.get(patientId));
    }

    @PatchMapping("/{patientId}")
    ResponseEntity<PatientDetailResponse> update(@PathVariable UUID patientId,
                                                @RequestBody UpdatePatientRequest request)
    {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(update.update(patientId, request));
    }

    @PostMapping
    ResponseEntity<PatientCreationResponse> create(@Valid @RequestBody CreatePatientRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
            .header("Cache-Control", "no-store")
            .body(creation.create(request));
    }
}
