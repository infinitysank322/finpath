package com.finpath.rebalance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.rules.WaterfallAllocator.Line;
import com.finpath.rules.WaterfallAllocator.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

class AllocationsJsonTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static List<Line> parse(String json) {
        return AllocationsJson.parse(OM, json);
    }

    private static Step anyStep() {
        return Step.values()[0];
    }

    @Test
    void parsesTheDocumentedFormatIncludingNullRefAndLongBoundary() {
        String step = anyStep().name();
        List<Line> lines = parse("[{\"step\":\"" + step + "\",\"ref\":null,\"amountPaise\":" + Long.MAX_VALUE
                + "},{\"step\":\"" + step + "\",\"ref\":\"r1\",\"amountPaise\":0}]");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).amount().paise()).isEqualTo(Long.MAX_VALUE);
        assertThat(lines.get(0).ref()).isNull();
        assertThat(lines.get(1).ref()).isEqualTo("r1");
        assertThat(parse("[]")).isEmpty();
    }

    @Test
    void everyDeviationThrowsAFixedMessageThatEchoesNothing() {
        String step = anyStep().name();
        List<String> bad = List.of(
                "{}", "\"x\"", "not json", "", "[1]", "[{}]",
                "[{\"step\":1,\"amountPaise\":5}]",
                "[{\"step\":\"" + step + "\"}]",
                "[{\"step\":\"" + step + "\",\"amountPaise\":5.5}]",
                "[{\"step\":\"" + step + "\",\"amountPaise\":-1}]",
                "[{\"step\":\"" + step + "\",\"amountPaise\":99999999999999999999}]",
                "[{\"step\":\"" + step + "\",\"ref\":7,\"amountPaise\":5}]",
                "[{\"step\":\"SECRET_UNKNOWN_STEP\",\"amountPaise\":5}]");
        for (String json : bad) {
            assertThatThrownBy(() -> parse(json)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("stored allocations are not in the documented format")
                    .message().doesNotContain("SECRET");
        }
    }

    @Test
    void nullInputIsRejectedNotNpe() {
        assertThatThrownBy(() -> parse(null)).isInstanceOf(IllegalStateException.class);
    }
}
