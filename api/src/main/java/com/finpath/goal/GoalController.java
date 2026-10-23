package com.finpath.goal;

import com.finpath.auth.CurrentUserId;
import com.finpath.goal.GoalService.GoalInput;
import com.finpath.goal.GoalService.GoalStatusView;
import com.finpath.goal.GoalService.GoalView;
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

/** Goal endpoints. The owner is always the authenticated principal; a userId in a body is ignored. */
@RestController
@RequestMapping("/goals")
public class GoalController {

    private final GoalService service;

    public GoalController(GoalService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<GoalView> create(@CurrentUserId UUID userId, @RequestBody GoalInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(userId, body));
    }

    @GetMapping
    public ResponseEntity<List<GoalView>> list(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GoalView> get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(userId, id));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<GoalStatusView> status(@CurrentUserId UUID userId, @PathVariable UUID id,
            @RequestParam(required = false) Long plannedMonthlyPaise) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.status(userId, id, plannedMonthlyPaise));
    }

    @PutMapping("/{id}")
    public ResponseEntity<GoalView> replace(@CurrentUserId UUID userId, @PathVariable UUID id,
            @RequestBody GoalInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replace(userId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
