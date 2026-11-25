package com.docukore.cabinet.patient;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Patient HTTP boundary; cabinet selection and authorization belong to the application services. */
@RestController
@RequestMapping("/api/patients")
public class PatientController
{
    /** Application service responsible for authorized patient creation. */
    private final PatientCreationService creation;

    /** Application service responsible for authorized directory reads. */
    private final PatientListingService listing;

    public PatientController(PatientCreationService creation, PatientListingService listing)
    {
        this.creation = creation;
        this.listing = listing;
    }

    @GetMapping
    ResponseEntity<PatientPageResponse> list(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String search)
    {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(listing.list(page, size, search));
    }

    @PostMapping
    ResponseEntity<PatientCreationResponse> create(@Valid @RequestBody CreatePatientRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
            .header("Cache-Control", "no-store")
            .body(creation.create(request));
    }
}
