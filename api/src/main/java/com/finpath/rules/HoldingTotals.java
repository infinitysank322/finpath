package com.finpath.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-23 asset-class aggregate. Pure: takes per-holding amounts (or partial sums), groups and sums them exactly, and returns every known class, in a
 * fixed order with zeros for classes the user does not hold, plus exact grand totals. Overflow throws.
 */
public final class HoldingTotals {

    /** Closed list of asset classes, in display order. */
    public static final List<String> ASSET_CLASSES = List.of("epf", "ppf", "nps", "mf", "stocks", "fd", "gold");

    /** Sums for one class. */
    public record ClassTotal(String assetClass, long investedPaise, long currentValuePaise,
            long monthlyContributionPaise, long count) {
    }

    public record Result(List<ClassTotal> classes, ClassTotal total) {
    }

    private HoldingTotals() {
    }

    /** Groups and sums items (one per holding, or partial sums) by class; unknown classes are rejected. */
    public static Result aggregate(List<ClassTotal> items) {
        Map<String, ClassTotal> byClass = new HashMap<>();
        for (ClassTotal i : items) {
            if (!ASSET_CLASSES.contains(i.assetClass())) {
                throw new IllegalArgumentException("unknown asset class");
            }
            byClass.merge(i.assetClass(), i, (a, b) -> new ClassTotal(a.assetClass(),
                    Math.addExact(a.investedPaise(), b.investedPaise()),
                    Math.addExact(a.currentValuePaise(), b.currentValuePaise()),
                    Math.addExact(a.monthlyContributionPaise(), b.monthlyContributionPaise()),
                    Math.addExact(a.count(), b.count())));
        }
        List<ClassTotal> out = new ArrayList<>();
        long inv = 0;
        long cur = 0;
        long mon = 0;
        long cnt = 0;
        for (String c : ASSET_CLASSES) {
            ClassTotal t = byClass.getOrDefault(c, new ClassTotal(c, 0, 0, 0, 0));
            out.add(new ClassTotal(c, t.investedPaise(), t.currentValuePaise(), t.monthlyContributionPaise(),
                    t.count()));
            inv = Math.addExact(inv, t.investedPaise());
            cur = Math.addExact(cur, t.currentValuePaise());
            mon = Math.addExact(mon, t.monthlyContributionPaise());
            cnt = Math.addExact(cnt, t.count());
        }
        return new Result(List.copyOf(out), new ClassTotal("total", inv, cur, mon, cnt));
    }
}
