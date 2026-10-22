package com.finpath.expense;

import com.finpath.auth.CurrentUserId;
import com.finpath.expense.ExpenseService.CreateResult;
import com.finpath.expense.ExpenseService.ExpenseInput;
import com.finpath.expense.ExpenseService.ExpenseView;
import com.finpath.expense.ExpenseService.ListResult;
import com.finpath.expense.ExpenseService.TagSummary;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expense endpoints. The owner is always the authenticated principal; a userId in a body is ignored.
 * POST honours an Idempotency-Key header: 201 for a new row, 200 with the original row on a replay.
 */
@RestController
@RequestMapping("/expenses")
public class ExpenseController {

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ExpenseView> create(@CurrentUserId UUID userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ExpenseInput body) {
        CreateResult r = service.create(userId, idempotencyKey, body);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(r.expense());
    }

    /** Both bounds inclusive (yyyy-MM-dd); neither means the current cycle. */
    @GetMapping
    public ResponseEntity<ListResult> list(@CurrentUserId UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId, from, to));
    }

    /** Need/want/waste/untagged totals for the range (neither bound = current cycle). */
    @GetMapping("/tag-summary")
    public ResponseEntity<TagSummary> tagSummary(@CurrentUserId UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.tagSummary(userId, from, to));
    }

    /** Body {@code {"tag":"NEED"|"WANT"|"WASTE"|null}}; null clears the tag back to untagged. */
    @PatchMapping("/{id}/tag")
    public ResponseEntity<ExpenseView> tag(@CurrentUserId UUID userId, @PathVariable UUID id,
            @RequestBody TagInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.setTag(userId, id, body == null ? null : body.tag()));
    }

    public record TagInput(String tag) {
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExpenseView> replace(@CurrentUserId UUID userId, @PathVariable UUID id,
            @RequestBody ExpenseInput body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replace(userId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
