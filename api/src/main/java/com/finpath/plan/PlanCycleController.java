package com.finpath.plan;

import com.finpath.auth.CurrentUserId;
import com.finpath.plan.PlanCycleService.PlanCycleView;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Budget-cycle endpoints. The user is always the authenticated principal; no id is accepted from the request. */
@RestController
@RequestMapping("/plan-cycles")
public class PlanCycleController {

    /** {@code startDay} is a day of month, 1..31 (29-31 clamp to the last day of short months). */
    public record StartDayRequest(Integer startDay) {
    }

    private final PlanCycleService service;

    public PlanCycleController(PlanCycleService service) {
        this.service = service;
    }

    /** The user's current cycle, created on first use. Idempotent. */
    @GetMapping("/current")
    public ResponseEntity<PlanCycleView> current(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(userId));
    }

    /** Changes users.cycle_start_day and re-derives the current cycle; returns the new current cycle. */
    @PutMapping("/start-day")
    public ResponseEntity<PlanCycleView> changeStartDay(@CurrentUserId UUID userId,
            @RequestBody StartDayRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.changeStartDay(userId, request == null ? null : request.startDay()));
    }
}
