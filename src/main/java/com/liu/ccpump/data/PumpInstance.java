package com.liu.ccpump.data;

/**
 * 一台已放置水泵的运行时数据。
 */
public final class PumpInstance {

    private final BlockKey key;
    private final String typeKey;

    private int water;
    private int charge;
    private boolean enabled = true;
    private boolean sourceAvailable;
    private long lastSourceCheck = -1L;

    private Network network;
    private long networkBuiltTick = -1L;
    private long networkVersion = -1L;
    private long nextEffectTick;

    public PumpInstance(BlockKey key, String typeKey) {
        this.key = key;
        this.typeKey = typeKey;
    }

    public BlockKey key() {
        return key;
    }

    public String typeKey() {
        return typeKey;
    }

    public int water() {
        return water;
    }

    public void water(int water) {
        this.water = water;
    }

    public int charge() {
        return charge;
    }

    public void charge(int charge) {
        this.charge = charge;
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean sourceAvailable() {
        return sourceAvailable;
    }

    public void sourceAvailable(boolean sourceAvailable) {
        this.sourceAvailable = sourceAvailable;
    }

    public long lastSourceCheck() {
        return lastSourceCheck;
    }

    public void lastSourceCheck(long lastSourceCheck) {
        this.lastSourceCheck = lastSourceCheck;
    }

    public Network network() {
        return network;
    }

    public void network(Network network) {
        this.network = network;
    }

    public long networkBuiltTick() {
        return networkBuiltTick;
    }

    public void networkBuiltTick(long networkBuiltTick) {
        this.networkBuiltTick = networkBuiltTick;
    }

    public long networkVersion() {
        return networkVersion;
    }

    public void networkVersion(long networkVersion) {
        this.networkVersion = networkVersion;
    }

    public long nextEffectTick() {
        return nextEffectTick;
    }

    public void nextEffectTick(long nextEffectTick) {
        this.nextEffectTick = nextEffectTick;
    }
}
