package com.finpath.networth;

import com.finpath.auth.CurrentUserId;
import com.finpath.networth.NetWorthService.SeriesView;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only net-worth series. Snapshots are written at cycle close, never through the API. */
@RestController
@RequestMapping("/net-worth")
public class NetWorthController {

    private final NetWorthService service;

    public NetWorthController(NetWorthService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<SeriesView> series(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.series(userId));
    }
}
