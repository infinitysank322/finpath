package com.finpath.budget;

import com.finpath.auth.CurrentUserId;
import com.finpath.budget.BudgetAlertService.Event;
import com.finpath.budget.BudgetService.BudgetInput;
import com.finpath.budget.BudgetService.Line;
import com.finpath.budget.BudgetService.StatusView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
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

/**
 * Budget endpoints. The owner is always the authenticated principal; a userId in a body is ignored.
 * {@code cycleStart} is any date inside the wanted cycle; absent means today (Asia/Kolkata).
 */
@RestController
@RequestMapping("/budgets")
public class BudgetController {

    public record Events(List<Event> events) {
    }

    private final BudgetService service;
    private final BudgetAlertService alerts;

    public BudgetController(BudgetService service, BudgetAlertService alerts) {
        this.service = service;
        this.alerts = alerts;
    }

    @PutMapping("/{categoryCode}")
    public ResponseEntity<Line> put(@CurrentUserId UUID userId, @PathVariable String categoryCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cycleStart,
            @RequestBody BudgetInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.put(userId, categoryCode, cycleStart, body));
    }

    @DeleteMapping("/{categoryCode}")
    public ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable String categoryCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cycleStart) {
        service.delete(userId, categoryCode, cycleStart);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public ResponseEntity<StatusView> status(@CurrentUserId UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cycleStart) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(userId, cycleStart));
    }

    @PostMapping("/evaluate")
    public ResponseEntity<Events> evaluate(@CurrentUserId UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cycleStart) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Events(alerts.evaluate(userId, cycleStart)));
    }
}
