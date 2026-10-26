package com.liu.ccpump.config;

import org.bukkit.Particle;

/**
 * 一台水泵的完整配置。
 */
public record PumpConfig(
        String key,
        String item,
        int capacity,
        int pumpRate,
        int pushRate,
        int bucketAmount,
        int chargeCapacity,
        int chargePerCycle,
        int durability,
        boolean allowToggle,
        Source source,
        Network network,
        Effects effects
) {

    public record Source(boolean required, int radius, boolean consume) {
    }

    public record Network(int maxPipes, int maxSprinklers, int maxDistance) {
    }

    public record Effects(boolean enabled, Particle particle, String sound,
                          float volume, float pitch, int interval) {
    }
}
