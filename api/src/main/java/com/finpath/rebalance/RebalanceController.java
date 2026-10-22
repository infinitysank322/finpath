package com.finpath.rebalance;

import com.finpath.auth.CurrentUserId;
import com.finpath.rebalance.RebalanceService.Preview;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unplanned-expense rebalance preview. The owner is the authenticated principal; nothing is stored. */
@RestController
@RequestMapping("/plan")
public class RebalanceController {

    /** {@code amountPaise} is a positive integer number of paise. */
    public record RebalanceRequest(Long amountPaise) {
    }

    private final RebalanceService service;

    public RebalanceController(RebalanceService service) {
        this.service = service;
    }

    @PostMapping("/rebalance")
    public ResponseEntity<Preview> rebalance(@CurrentUserId UUID userId, @RequestBody RebalanceRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.preview(userId, body == null ? null : body.amountPaise()));
    }
}
