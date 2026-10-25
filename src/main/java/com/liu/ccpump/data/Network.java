package com.liu.ccpump.data;

import net.momirealms.customcrops.api.core.world.Pos3;

import java.util.List;

/**
 * 一台水泵能覆盖的管网：水管列表、洒水器列表、相邻水泵数量。
 */
public record Network(List<Pos3> pipes, List<Pos3> sprinklers, int pumps) {

    public static final Network EMPTY = new Network(List.of(), List.of(), 0);
}
