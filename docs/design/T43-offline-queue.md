# T43 - Offline expense queue (as built)

Code: `web/src/app/expense/expense-queue.service.ts`, `pending-expenses.component.*`. Queue lives in localStorage
(`finpath.expenseQueue.v1`), payload is `ExpenseInput` plus a per-item Idempotency-Key. Flush is single-flight and
serial; 201 and 200 (replay) clear an item; 400 and other 4xx (except 401/403/408/429) go to "needs attention";
network, timeout (30 s), 5xx, 401, 408, 429 retry with backoff 5 s / 30 s / 2 min (cap) and on `online`,
`visibilitychange`, app start and online enqueue; 403 pauses until the next trigger. Writes re-read storage and merge
by key; `storage` events refresh memory. Dedupe against imported/SMS rows stays with the API.

## Dependencies for T33 (not implemented in T43)

1. **Per-user namespace.** The storage key is global to the browser profile. On sign-in as a different user,
   queued items from the previous user would be uploaded under the new identity. T33 must namespace the key by
   user id (or clear the queue on sign-out/user change, after warning about unsent items).
2. **Flush after sign-in.** A 401 leaves items pending and retries on backoff, but nothing triggers an immediate
   flush when a session is established. T33 must call `ExpenseQueueService.flush()` once the JWT is stored.
