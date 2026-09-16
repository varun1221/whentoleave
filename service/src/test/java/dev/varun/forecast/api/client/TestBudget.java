package dev.varun.forecast.api.client;

/** A {@link CallBudget} of a fixed size that remembers what was taken from it. */
final class TestBudget implements CallBudget {

    private int available;
    private int spent;

    TestBudget(int size) {
        this.available = size;
    }

    @Override
    public boolean tryAcquire() {
        if (available == 0) {
            return false;
        }
        available--;
        spent++;
        return true;
    }

    @Override
    public void refund() {
        available++;
        spent--;
    }

    int spent() {
        return spent;
    }
}
