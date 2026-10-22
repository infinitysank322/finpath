package com.finpath.loan;

import com.finpath.auth.CurrentUserId;
import com.finpath.loan.LoanService.LoanDetailView;
import com.finpath.loan.LoanService.LoanInput;
import com.finpath.loan.LoanService.LoanView;
import com.finpath.loan.LoanService.PayoffPlanView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Loan endpoints. The owner is always the authenticated principal; a userId in a body is ignored. */
@RestController
@RequestMapping("/loans")
public class LoanController {

    private final LoanService service;

    public LoanController(LoanService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<LoanView> create(@CurrentUserId UUID userId, @RequestBody LoanInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(userId, body));
    }

    @GetMapping
    public ResponseEntity<List<LoanView>> list(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId));
    }

    @GetMapping("/payoff-plan")
    public ResponseEntity<PayoffPlanView> payoffPlan(@CurrentUserId UUID userId,
            @RequestParam(required = false) String strategy) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.payoffPlan(userId, strategy));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LoanView> get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(userId, id));
    }

    @GetMapping("/{id}/detail")
    public ResponseEntity<LoanDetailView> detail(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(userId, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LoanView> replace(@CurrentUserId UUID userId, @PathVariable UUID id,
            @RequestBody LoanInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replace(userId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
