package com.finpath.income;

import com.finpath.auth.CurrentUserId;
import com.finpath.income.IncomeService.CycleTotalView;
import com.finpath.income.IncomeService.IncomeInput;
import com.finpath.income.IncomeService.IncomeView;
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

/** Income endpoints. The owner is always the authenticated principal; a userId in a body is ignored. */
@RestController
@RequestMapping("/income-sources")
public class IncomeController {

    private final IncomeService service;

    public IncomeController(IncomeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IncomeView> create(@CurrentUserId UUID userId, @RequestBody IncomeInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(userId, body));
    }

    @GetMapping
    public ResponseEntity<List<IncomeView>> list(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId));
    }

    @GetMapping("/cycle-total")
    public ResponseEntity<CycleTotalView> cycleTotal(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.cycleTotal(userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<IncomeView> replace(@CurrentUserId UUID userId, @PathVariable UUID id,
            @RequestBody IncomeInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replace(userId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
