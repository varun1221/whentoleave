package dev.varun.forecast.api.service;

import dev.varun.forecast.api.client.CallBudget;

/**
 * One fill's share of the global daily ceiling.
 *
 * <p>Reserved in a block up front — one row lock for a whole grid rather than one per
 * slot — and then topped up a call at a time when a retry needs more than the block
 * covered. A retry has to be paid for like any other request (§9.4), but the block is
 * sized to the slots, not to the attempts those slots might take, so without a top-up a
 * single retry would silently starve some other slot of the fill.
 *
 * <p>Tops up through {@link QuotaService} rather than counting locally, because §7 makes
 * that counter the hard stop: a retry it cannot fund is one the caller must do without.
 *
 * <p>A top-up is a separate reservation against whatever day it was taken on, which need
 * not be the block's day — a fill can straddle midnight. So a refunded top-up goes back
 * to its own reservation immediately rather than being banked here, and {@link #unspent}
 * only ever reports calls belonging to the block. Handing a top-up back to the block
 * would credit one day's ceiling from another's.
 *
 * <p>A fill spends it from several threads at once, one per call. The counts are shared
 * and locked; which reservation an acquire came from is not, because a refund has to
 * undo its own call's acquire, not whichever call acquired last. So each call spends
 * through its own {@link #forCall} view; the budget's own methods are one more, for a
 * caller that only ever makes one call at a time.
 */
final class QuotaBudget implements CallBudget {

    private final QuotaService quotas;
    private final CallView single = new CallView();
    private int available;
    private int spent;

    QuotaBudget(QuotaService quotas, QuotaService.Reservation block) {
        this.quotas = quotas;
        this.available = block.granted();
    }

    /** A view for one call, which may run alongside others spending the same budget. */
    CallBudget forCall() {
        return new CallView();
    }

    @Override
    public boolean tryAcquire() {
        return single.tryAcquire();
    }

    @Override
    public void refund() {
        single.refund();
    }

    /** Calls that reached TomTom, and so are owed to neither limit. */
    synchronized int spent() {
        return spent;
    }

    /** Calls still owed back to the block, to be released against the block's day. */
    synchronized int unspent() {
        return available;
    }

    private synchronized boolean takeFromBlock() {
        if (available == 0) {
            return false;
        }
        available--;
        spent++;
        return true;
    }

    private synchronized void returnToBlock() {
        available++;
        spent--;
    }

    private synchronized void spendTopUp() {
        spent++;
    }

    private synchronized void unspendTopUp() {
        spent--;
    }

    /** One call's spending, which remembers where its own last acquire came from. */
    private final class CallView implements CallBudget {

        /** The top-up the last acquire came from, or null if it came from the block. */
        private QuotaService.Reservation lastTopUp;

        @Override
        public boolean tryAcquire() {
            if (takeFromBlock()) {
                lastTopUp = null;
                return true;
            }
            // Always exactly one: a top-up is a retry about to be sent, so asking for
            // more would reserve calls with nothing to spend them on. Outside the lock,
            // since it is a database round trip.
            QuotaService.Reservation topUp = quotas.reserve(1);
            if (topUp.granted() == 0) {
                return false;
            }
            spendTopUp();
            lastTopUp = topUp;
            return true;
        }

        @Override
        public void refund() {
            if (lastTopUp == null) {
                returnToBlock();
                return;
            }
            unspendTopUp();
            quotas.release(lastTopUp, 1);
            lastTopUp = null;
        }
    }
}
