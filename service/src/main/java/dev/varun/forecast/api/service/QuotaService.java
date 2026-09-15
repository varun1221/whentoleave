package dev.varun.forecast.api.service;

import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.repo.QuotaRepository;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Calls granted, and the day they were granted from. A release goes back to that day,
     * so a lookup straddling midnight cannot push the new day past its ceiling.
     */
    public record Reservation(LocalDate day, int granted) {}

    private final QuotaRepository quota;
    private final ForecastProperties props;
    private final QuotaDay day;

    public QuotaService(QuotaRepository quota, ForecastProperties props, QuotaDay day) {
        this.quota = quota;
        this.props = props;
        this.day = day;
    }

    /**
     * Reserves up to {@code wanted} calls against today's budget.
     *
     * <p>Reserve-then-spend rather than spend-then-count: a crash after the calls but
     * before the increment would otherwise let the ceiling drift upward silently. The
     * cost of the opposite failure is a few unspent reservations, which is the cheaper
     * mistake.
     *
     * @return how many calls the caller may actually make, possibly zero, and the day
     *     they count against
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(int wanted) {
        LocalDate today = day.today();
        if (wanted <= 0) {
            return new Reservation(today, 0);
        }
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
        return new Reservation(today, granted);
    }

    /**
     * Hands back reserved calls that were never sent to TomTom, such as when no key is
     * configured or the connection failed before a request went out. A call that was
     * sent stays spent even if it failed: TomTom counted it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Reservation reservation, int unsent) {
        if (unsent <= 0) {
            return;
        }
        quota.release(reservation.day(), unsent);
    }

    /** Calls still available today. Drives the quota endpoint and the UI banner. */
    @Transactional(readOnly = true)
    public int remaining() {
        LocalDate today = day.today();
        Integer used = quota.findById(today).map(q -> q.getCallsMade()).orElse(0);
        return Math.max(0, props.quota().dailyCeiling() - used);
    }

    /** When today's budget refills. */
    public Instant resetsAt() {
        return day.resetsAt();
    }

    public int dailyCeiling() {
        return props.quota().dailyCeiling();
    }
}
