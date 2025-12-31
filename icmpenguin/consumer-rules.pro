-keep class me.impa.icmpenguin.ProbeManager {
    public void probeCallback(int, me.impa.icmpenguin.ProbeResult);
}

-keep interface me.impa.icmpenguin.ProbeResult

-keep class me.impa.icmpenguin.ProbeResult$* {
    public <init>(...);
    public <fields>;
}
