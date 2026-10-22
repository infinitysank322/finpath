package com.finpath.recurring;

import com.finpath.auth.CurrentUserId;
import com.finpath.recurring.RecurringService.MaterialiseResult;
import com.finpath.recurring.RecurringService.TemplateInput;
import com.finpath.recurring.RecurringService.TemplateView;
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
import org.springframework.web.bind.annotation.RestController;

/** Recurring-bill endpoints. The owner is always the authenticated principal; a userId in a body is ignored. */
@RestController
@RequestMapping("/recurring-templates")
public class RecurringController {

    private final RecurringService service;

    public RecurringController(RecurringService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TemplateView> create(@CurrentUserId UUID userId, @RequestBody TemplateInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(userId, body));
    }

    @GetMapping
    public ResponseEntity<List<TemplateView>> list(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TemplateView> replace(@CurrentUserId UUID userId, @PathVariable UUID id,
            @RequestBody TemplateInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replace(userId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** Posts the current cycle's instalments; safe to call repeatedly (idempotent per template and cycle). */
    @PostMapping("/materialise")
    public ResponseEntity<MaterialiseResult> materialise(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.materialise(userId));
    }
}
