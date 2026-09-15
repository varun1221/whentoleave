package dev.varun.forecast.api.web;

import dev.varun.forecast.api.service.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QuotaController {

    private final QuotaService quotas;

    public QuotaController(QuotaService quotas) {
        this.quotas = quotas;
    }

    /** Remaining daily calls, so the UI can show the limit rather than hide it. */
    @GetMapping("/quota")
    public QuotaStatus quota() {
        int remaining = quotas.remaining();
        return new QuotaStatus(remaining, quotas.dailyCeiling(), remaining > 0);
    }

    public record QuotaStatus(int remaining, int dailyCeiling, boolean lookupsAvailable) {}
}
