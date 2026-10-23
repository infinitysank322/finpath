package com.finpath.fi;

import com.finpath.fi.FiPlannerService.FiPlanView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FR-21 planner. Educational projection only; the response carries every assumption used. */
@RestController
@RequestMapping("/fi-planner")
public class FiPlannerController {

    private final FiPlannerService service;

    public FiPlannerController(FiPlannerService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<FiPlanView> plan(@RequestParam(required = false) Long todayMonthlyExpensePaise,
            @RequestParam(required = false) Long todayMonthlyPassivePaise,
            @RequestParam(required = false) Long openingBalancePaise,
            @RequestParam(required = false) Long monthlySipPaise,
            @RequestParam(required = false) String stepUp,
            @RequestParam(required = false) Integer yearsToFi) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.plan(todayMonthlyExpensePaise,
                todayMonthlyPassivePaise, openingBalancePaise, monthlySipPaise, stepUp, yearsToFi));
    }
}
