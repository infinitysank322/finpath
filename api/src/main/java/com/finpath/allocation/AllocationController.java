package com.finpath.allocation;

import com.finpath.allocation.AllocationService.AllocationView;
import com.finpath.auth.CurrentUserId;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FR-22 allocation for the caller's current cycle. The owner is always the authenticated principal. */
@RestController
@RequestMapping("/allocation")
public class AllocationController {

    private final AllocationService service;

    public AllocationController(AllocationService service) {
        this.service = service;
    }

    /** Runs the waterfall and stores it on the open cycle. Idempotent: the same inputs give the same stored result. */
    @PostMapping("/run")
    public ResponseEntity<AllocationView> run(@CurrentUserId UUID userId,
            @RequestParam(required = false) String prepayShare) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.run(userId, prepayShare));
    }

    @GetMapping
    public ResponseEntity<AllocationView> current(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(userId));
    }
}
