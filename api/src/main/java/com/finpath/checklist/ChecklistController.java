package com.finpath.checklist;

import com.finpath.auth.CurrentUserId;
import com.finpath.checklist.ChecklistService.ItemView;
import com.finpath.checklist.ChecklistService.ListView;
import com.finpath.checklist.ChecklistService.StreakView;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Checklist endpoints. The owner is always the authenticated principal; nothing else names a user. */
@RestController
@RequestMapping("/checklist")
public class ChecklistController {

    private final ChecklistService service;

    public ChecklistController(ChecklistService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public ResponseEntity<ListView> generate(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.generate(userId));
    }

    @GetMapping
    public ResponseEntity<ListView> list(@CurrentUserId UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cycleStart) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId, cycleStart));
    }

    @PostMapping("/items/{id}/done")
    public ResponseEntity<ItemView> done(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.markDone(userId, id));
    }

    @GetMapping("/streak")
    public ResponseEntity<StreakView> streak(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.streak(userId));
    }
}
