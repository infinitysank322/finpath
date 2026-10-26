package com.finpath.dashboard;

import com.finpath.auth.CurrentUserId;
import com.finpath.dashboard.DashboardService.DashboardView;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Home-screen aggregate (T74). No parameters: the owner is the authenticated principal only. */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<DashboardView> get(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.dashboard(userId));
    }
}
