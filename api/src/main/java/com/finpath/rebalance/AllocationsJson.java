package com.finpath.rebalance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.rules.Money;
import com.finpath.rules.WaterfallAllocator;
import java.util.ArrayList;
import java.util.List;

/**
 * Format of {@code plan_cycle.allocations}: a JSON array of
 * {@code {"step":"<WaterfallAllocator.Step name>","ref":string|null,"amountPaise":long}}.
 * Parsing only; no arithmetic. Any deviation throws {@link IllegalStateException} (a server fault,
 * since only the allocation run writes this column) with a fixed message that echoes no content.
 */
final class AllocationsJson {

    private AllocationsJson() {
    }

    static List<WaterfallAllocator.Line> parse(ObjectMapper mapper, String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw bad();
            }
            List<WaterfallAllocator.Line> lines = new ArrayList<>();
            for (JsonNode n : root) {
                JsonNode step = n.get("step");
                JsonNode ref = n.get("ref");
                JsonNode amount = n.get("amountPaise");
                if (step == null || !step.isTextual() || amount == null || !amount.isIntegralNumber()
                        || !amount.canConvertToLong() || amount.asLong() < 0
                        || (ref != null && !ref.isNull() && !ref.isTextual())) {
                    throw bad();
                }
                lines.add(new WaterfallAllocator.Line(WaterfallAllocator.Step.valueOf(step.asText()),
                        ref == null || ref.isNull() ? null : ref.asText(), Money.ofPaise(amount.asLong())));
            }
            return lines;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw bad();
        }
    }

    private static IllegalStateException bad() {
        return new IllegalStateException("stored allocations are not in the documented format");
    }
}
