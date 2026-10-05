package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.client.CallBudget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The block of calls a fill spends, and what happens when it needs one more than it
 * reserved. Against the real quota, because the thing under test is the arithmetic
 * between the two.
 */
class QuotaBudgetTest extends DatabaseTest {

    private static final int CEILING = 150;

    @Autowired private QuotaService quotas;

    private QuotaBudget budgetOf(int calls) {
        return new QuotaBudget(quotas, quotas.reserve(calls));
    }

    @Test
    void spendsTheBlockBeforeAskingForMore() {
        QuotaBudget budget = budgetOf(2);

        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());

        assertEquals(2, budget.spent());
        assertEquals(0, budget.unspent());
        assertEquals(CEILING - 2, quotas.remaining(), "the block was reserved, not more");
    }

    @Test
    void whatTheBlockDidNotCoverIsOwedBack() {
        QuotaBudget budget = budgetOf(3);

        assertTrue(budget.tryAcquire());

        assertEquals(1, budget.spent());
        assertEquals(2, budget.unspent(), "two slots were never attempted");
    }

    /** A retry the block did not cover is paid for out of the day's remaining ceiling. */
    @Test
    void aRetryBeyondTheBlockIsToppedUpFromTheCeiling() {
        QuotaBudget budget = budgetOf(1);
        budget.tryAcquire();

        assertTrue(budget.tryAcquire(), "the retry was funded");

        assertEquals(2, budget.spent());
        assertEquals(CEILING - 2, quotas.remaining(), "the top-up counts against the day");
    }

    /** §7: the ceiling is the hard stop, so a top-up it cannot fund is refused. */
    @Test
    void aTopUpIsRefusedOnceTheCeilingIsReached() {
        quotas.reserve(CEILING - 1);
        QuotaBudget budget = budgetOf(1);
        budget.tryAcquire();

        assertFalse(budget.tryAcquire(), "nothing left to top up with");

        assertEquals(1, budget.spent());
        assertEquals(0, quotas.remaining());
    }

    /**
     * A top-up belongs to the day it was taken on, which a fill straddling midnight need
     * not share with the block. Refunding it into the block would hand one day's ceiling
     * a call reserved from another's, so it goes straight back where it came from and
     * never shows up as owed to the block.
     */
    @Test
    void arefundedTopUpGoesBackToItsOwnReservation() {
        QuotaBudget budget = budgetOf(1);
        budget.tryAcquire();
        budget.tryAcquire();

        budget.refund();

        assertEquals(1, budget.spent());
        assertEquals(0, budget.unspent(), "the top-up is not owed to the block");
        assertEquals(CEILING - 1, quotas.remaining(), "it was released already");
    }

    /** A call from the block, though, is owed back to the block. */
    @Test
    void aRefundedBlockCallIsOwedBackToTheBlock() {
        QuotaBudget budget = budgetOf(2);
        budget.tryAcquire();

        budget.refund();

        assertEquals(0, budget.spent());
        assertEquals(2, budget.unspent());
    }

    /**
     * A fill sends its calls concurrently, so a refund must undo the acquire its own call
     * made. Tracked for the budget as a whole, one call's connection failure would hand
     * back whichever call another thread acquired last.
     */
    @Test
    void eachCallRefundsItsOwnAcquire() {
        QuotaBudget budget = budgetOf(1);
        CallBudget fromBlock = budget.forCall();
        CallBudget toppedUp = budget.forCall();
        fromBlock.tryAcquire();
        toppedUp.tryAcquire();

        fromBlock.refund();

        assertEquals(1, budget.spent(), "the top-up still went out");
        assertEquals(1, budget.unspent(), "the refunded call is owed back to the block");
        assertEquals(CEILING - 2, quotas.remaining(), "the top-up stays reserved");
    }
}
