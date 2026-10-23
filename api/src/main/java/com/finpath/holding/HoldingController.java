package com.finpath.holding;

import com.finpath.auth.CurrentUserId;
import com.finpath.holding.HoldingService.AggregateView;
import com.finpath.holding.HoldingService.HoldingInput;
import com.finpath.holding.HoldingService.HoldingView;
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

/** Holding endpoints. The owner is always the authenticated principal; a userId in a body is ignored. */
@RestController
@RequestMapping("/holdings")
public class HoldingController {

    private final HoldingService service;

    public HoldingController(HoldingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<HoldingView> create(@CurrentUserId UUID userId, @RequestBody HoldingInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(userId, body));
    }

    @GetMapping
    public ResponseEntity<List<HoldingView>> list(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId));
    }

    /** Declared before /{id}; the literal path wins in Spring MVC regardless, and the id is a UUID. */
    @GetMapping("/aggregate")
    public ResponseEntity<AggregateView> aggregate(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.aggregate(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HoldingView> get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(userId, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HoldingView> replace(@CurrentUserId UUID userId, @PathVariable UUID id,
            @RequestBody HoldingInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replace(userId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
