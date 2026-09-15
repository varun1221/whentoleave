package dev.varun.forecast.api.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Present so Spring Data has an entity to hang the repository off; the counter itself is
 * read and written with the native queries in {@link QuotaRepository}, because the
 * correctness of the reserve step depends on an explicit row lock.
 */
@Entity
@Table(name = "daily_quota")
public class DailyQuota {

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "calls_made", nullable = false)
    private int callsMade;

    protected DailyQuota() {}

    public LocalDate getDay() {
        return day;
    }

    public int getCallsMade() {
        return callsMade;
    }
}
