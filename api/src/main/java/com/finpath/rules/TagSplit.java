package com.finpath.rules;

import java.util.Collection;

/**
 * Need/want/waste split of a period's outflow (FR-06). Every amount lands in exactly one bucket: a
 * null or unrecognised tag is {@code untagged}, never silently a need, so the four buckets always
 * add up to the total outflow. Integer paise; overflow throws rather than wrapping.
 */
public final class TagSplit {

    private TagSplit() {
    }

    public enum Tag { NEED, WANT, WASTE }

    /** One outflow amount with its tag; {@code tag} null means untagged. */
    public record Entry(long amountPaise, Tag tag) {
        public Entry {
            if (amountPaise <= 0) {
                throw new IllegalArgumentException("amountPaise must be positive");
            }
        }
    }

    public record Split(long needPaise, long wantPaise, long wastePaise, long untaggedPaise, long totalPaise) {
    }

    public static Split split(Collection<Entry> entries) {
        long need = 0;
        long want = 0;
        long waste = 0;
        long untagged = 0;
        for (Entry e : entries) {
            if (e.tag() == null) {
                untagged = Math.addExact(untagged, e.amountPaise());
                continue;
            }
            switch (e.tag()) {
                case NEED -> need = Math.addExact(need, e.amountPaise());
                case WANT -> want = Math.addExact(want, e.amountPaise());
                case WASTE -> waste = Math.addExact(waste, e.amountPaise());
                default -> throw new IllegalStateException("unhandled tag " + e.tag());
            }
        }
        long total = Math.addExact(Math.addExact(need, want), Math.addExact(waste, untagged));
        return new Split(need, want, waste, untagged, total);
    }
}
