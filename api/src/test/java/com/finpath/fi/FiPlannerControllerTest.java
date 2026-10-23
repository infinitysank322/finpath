package com.finpath.fi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finpath.rules.RuleConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** T63 HTTP shape: no-store, fixed 400, disclaimer and assumptions in the body. */
class FiPlannerControllerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FiPlannerController(new FiPlannerService(RuleConfig.defaults())))
            .setControllerAdvice(new FiExceptionHandler()).build();

    @Test
    void okResponseIsNoStoreAndCarriesDisclaimerAndAssumptions() throws Exception {
        mvc.perform(get("/fi-planner").param("todayMonthlyExpensePaise", "5000000").param("yearsToFi", "13"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.disclaimer").value(FiPlannerService.DISCLAIMER))
                .andExpect(jsonPath("$.assumptions.inflation").exists())
                .andExpect(jsonPath("$.bands.length()").value(3));
    }

    @Test
    void badInputIsAFixedFourHundred() throws Exception {
        String body = mvc.perform(get("/fi-planner").param("todayMonthlyExpensePaise", "5000000"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("invalid_request");
    }
}
