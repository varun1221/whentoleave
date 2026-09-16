package dev.varun.forecast.api.service;

import dev.varun.forecast.api.client.CallBudget;

/**
 * One fill's share of the global daily ceiling.
 *
 * <p>Reserved in a block up front — one row lock for a whole grid rather than one per
 * slot — and then topped up a call at a time when a retry needs more than the block
 * covered. A retry has to be paid for like any other request (§9.4), but the block is
 * sized to the slots, not to the attempts those slots might take, so without a top-up a
 * single retry would silently starve the last slot of the fill.
 *
 * <p>Tops up through {@link QuotaService} rather than counting locally, because §7 makes
 * that counter the hard stop: a retry it cannot fund is one the caller must do without.
 *
 * <p>A top-up is a separate reservation against whatever day it was taken on, which need
 * not be the block's day — a fill can straddle midnight. So a refunded top-up goes back
 * to its own reservation immediately rather than being banked here, and {@link #unspent}
 * only ever reports calls belonging to the block. Handing a top-up back to the block
 * would credit one day's ceiling from another's.
 */
final class QuotaBudget implements CallBudget {

    private final QuotaService quotas;
    private int available;
    private int spent;

    /** The top-up the last acquire came from, or null if it came from the block. */
    private QuotaService.Reservation lastTopUp;

    QuotaBudget(QuotaService quotas, QuotaService.Reservation block) {
        this.quotas = quotas;
        this.available = block.granted();
    }

    @Override
    public boolean tryAcquire() {
        if (available > 0) {
            available--;
            spent++;
            lastTopUp = null;
            return true;
        }
        // Always exactly one: a top-up is a retry about to be sent, so asking for more
        // would reserve calls with nothing to spend them on.
        QuotaService.Reservation topUp = quotas.reserve(1);
        if (topUp.granted() == 0) {
            return false;
        }
        spent++;
        lastTopUp = topUp;
        return true;
    }

    @Override
    public void refund() {
        spent--;
        if (lastTopUp == null) {
            available++;
            return;
        }
        quotas.release(lastTopUp, 1);
        lastTopUp = null;
    }

    /** Calls that reached TomTom, and so are owed to neither limit. */
    int spent() {
        return spent;
    }

    /** Calls still owed back to the block, to be released against the block's day. */
    int unspent() {
        return available;
    }
}
