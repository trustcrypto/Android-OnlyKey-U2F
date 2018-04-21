package to.crp.android.onlykeyu2f;

/**
 * Interface for classes wishing to be notified of {@link OnlyKey} events.
 */
interface OKListener {
    /**
     * OnlyKey is done. Use {@link OKEvent#getBoolVal()} to determine if any U2F processing
     * occurred (otherwise the time-set operation completed).
     *
     * @param event The event object.
     */
    void okDone(OKEvent event);

    /**
     * OnlyKey has had an error.
     *
     * @param event The event object.
     */
    void okError(OKEvent event);

    /**
     * OnlyKey has a message for the user.
     *
     * @param event The event object.
     */
    void okMessage(OKEvent event);

    /**
     * OnlyKey initialized state has changed. Use {@link OKEvent#getBoolVal()}.
     *
     * @param event The event object.
     */
    void okSetInitialized(OKEvent event);

    /**
     * OnlyKey locked state has changed. Use {@link OKEvent#getBoolVal()}.
     *
     * @param event The event object.
     */
    void okSetLocked(OKEvent event);

    /**
     * OnlyKey has set the device time.
     *
     * @param event The event object.
     */
    void okSetTime(OKEvent event);

    /**
     * OnlyKey has a response to a U2F register or sign request.
     *
     * @param event The event object.
     */
    void u2fResponse(OKEvent event);
}
