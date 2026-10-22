package com.finpath.dpdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.finpath.auth.CurrentUserId;
import com.finpath.dpdp.ConsentService.Consent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DPDP endpoints. The user is always the authenticated principal ({@code @CurrentUserId}); no id
 * is accepted from a path, query or body.
 */
@RestController
@RequestMapping("/me")
public class MeController {

    public record ConsentRequest(String purpose, String statementVersion) {
    }

    private final DataSubjectService data;
    private final ConsentService consents;
    private final AccountDeletionService deletion;

    public MeController(DataSubjectService data, ConsentService consents, AccountDeletionService deletion) {
        this.data = data;
        this.consents = consents;
        this.deletion = deletion;
    }

    @GetMapping("/export")
    public ResponseEntity<JsonNode> export(@CurrentUserId UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "attachment; filename=\"finpath-export.json\"")
                .body(data.export(userId));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@CurrentUserId UUID userId) {
        deletion.delete(userId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** The statement to show, and the caller's live consent (null when none). */
    @GetMapping("/consent")
    public ResponseEntity<Map<String, Object>> consent(@CurrentUserId UUID userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statement", ConsentStatement.CURRENT);
        Consent c = consents.current(userId).orElse(null);
        body.put("given", c != null);
        body.put("consent", c);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/consent")
    public ResponseEntity<Consent> grant(@CurrentUserId UUID userId, @RequestBody ConsentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(consents.grant(userId, request.purpose(), request.statementVersion()));
    }

    /** Withdraw consent: the account is purged after the grace period; DELETE /me erases it now. */
    @DeleteMapping("/consent")
    public ResponseEntity<Void> withdraw(@CurrentUserId UUID userId) {
        consents.withdraw(userId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
