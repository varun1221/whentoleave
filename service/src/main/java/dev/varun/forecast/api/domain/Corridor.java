package dev.varun.forecast.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An origin/destination pair. The five Phase 1 corridors carry a {@code slug} and are
 * flagged {@code seeded}; corridors created by a visitor lookup have a null slug and are
 * identified by their coordinates.
 *
 * <p>No Lombok — the spec rules it out, and a handful of accessors is cheaper than a
 * build-time dependency that can break a compile six months from now.
 */
@Entity
@Table(name = "corridor")
public class Corridor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slug", unique = true)
    private String slug;

    @Column(name = "origin_coord", nullable = false)
    private String originCoord;

    @Column(name = "dest_coord", nullable = false)
    private String destCoord;

    @Column(name = "label")
    private String label;

    @Column(name = "seeded", nullable = false)
    private boolean seeded;

    @Column(name = "first_seen_at", nullable = false, insertable = false, updatable = false)
    private Instant firstSeenAt;

    /** JPA requires a no-arg constructor. */
    protected Corridor() {}

    public Corridor(String slug, String originCoord, String destCoord, String label,
            boolean seeded) {
        this.slug = slug;
        this.originCoord = originCoord;
        this.destCoord = destCoord;
        this.label = label;
        this.seeded = seeded;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getOriginCoord() {
        return originCoord;
    }

    public String getDestCoord() {
        return destCoord;
    }

    public String getLabel() {
        return label;
    }

    public boolean isSeeded() {
        return seeded;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }
}
