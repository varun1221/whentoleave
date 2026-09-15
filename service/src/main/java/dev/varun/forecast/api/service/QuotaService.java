package dev.varun.forecast.api.service;

import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.repo.QuotaRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The global daily ceiling on outbound API calls.
 *
 * <p>TomTom freemium has no payment method attached, so an overrun cannot produce a bill
 * — but it also means there is no vendor-side cap to fall back on. This counter is the
 * hard stop that makes a public endpoint safe to expose, and it lives in Postgres rather
 * than memory because Cloud Run scales to zero and would otherwise forget it.
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final QuotaRepository quota;
    private final ForecastProperties props;

    public QuotaService(QuotaRepository quota, ForecastProperties props) {
        this.quota = quota;
        this.props = props;
    }

    /**
     * Reserves up to {@code wanted} calls against today's budget.
     *
     * <p>Reserve-then-spend rather than spend-then-count: a crash after the calls but
     * before the increment would otherwise let the ceiling drift upward silently. The
     * cost of the opposite failure is a few unspent reservations, which is the cheaper
     * mistake.
     *
     * @return how many calls the caller may actually make, possibly zero
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reserve(int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        quota.ensureRow(today);
        int used = quota.lockAndRead(today);
        int ceiling = props.quota().dailyCeiling();
        int granted = Math.max(0, Math.min(wanted, ceiling - used));
        if (granted > 0) {
            quota.setCalls(today, used + granted);
        }
        if (granted < wanted) {
            log.warn("daily quota limiting: wanted={} granted={} used={} ceiling={}",
                    wanted, granted, used, ceiling);
        }
        return granted;
    }

    /** Calls still available today. Drives the quota endpoint and the UI banner. */
    @Transactional(readOnly = true)
    public int remaining() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Integer used = quota.findById(today).map(q -> q.getCallsMade()).orElse(0);
        return Math.max(0, props.quota().dailyCeiling() - used);
    }

    public int dailyCeiling() {
        return props.quota().dailyCeiling();
    }
}
