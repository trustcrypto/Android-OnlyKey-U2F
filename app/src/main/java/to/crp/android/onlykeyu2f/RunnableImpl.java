package to.crp.android.onlykeyu2f;

abstract class RunnableImpl implements Runnable {

    private boolean cancelled = false;

    void cancel() {
        this.cancelled = true;
    }

    public void interrupt() {
        Thread.currentThread().interrupt();
    }

    boolean isCancelledOrInterrupted() {
        return Thread.currentThread().isInterrupted() || cancelled;
    }
}
