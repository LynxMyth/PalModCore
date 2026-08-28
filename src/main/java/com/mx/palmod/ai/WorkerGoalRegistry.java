package com.mx.palmod.ai;

import com.mojang.logging.LogUtils;
import com.mx.palmod.behavior.PalBehavior;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps station_mode.worker_type strings to worker goal factories.
 * Adding a job = one {@link AbstractStationWorkerGoal} subclass + one register() call.
 * Unknown types fall back to "harvester" with a warning (mirrors PalBehaviorManager's
 * graceful handling of unknown entities).
 */
public final class WorkerGoalRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    @FunctionalInterface
    public interface Factory {
        Goal create(Mob mob, PalBehavior behavior);
    }

    private static final Map<String, Factory> FACTORIES = new HashMap<>();

    static {
        register("harvester", (mob, behavior) -> new LeafcutterHarvesterGoal(
                mob, behavior.getHarvestRadius(), behavior.getWanderRadius()));
        register("lumberjack", (mob, behavior) -> new TreeFellerGoal(
                mob, behavior.getHarvestRadius(), behavior.getWanderRadius(), behavior.getLogCap()));
        register("trader", (mob, behavior) -> new TraderGoal(
                mob, behavior.getHarvestRadius(), behavior.getWanderRadius()));
        register("sorter", (mob, behavior) -> new SorterGoal(
                mob, behavior.getHarvestRadius(), behavior.getWanderRadius()));
        register("miner", (mob, behavior) -> new OreMinerGoal(
                mob, behavior.getHarvestRadius(), behavior.getWanderRadius(), behavior.getOreTag()));
    }

    private WorkerGoalRegistry() {}

    public static void register(String workerType, Factory factory) {
        FACTORIES.put(workerType, factory);
    }

    public static Goal create(String workerType, Mob mob, PalBehavior behavior) {
        Factory factory = FACTORIES.get(workerType);
        if (factory == null) {
            LOGGER.warn("Unknown station worker_type '{}' for {}, falling back to 'harvester'",
                    workerType, mob.getType());
            factory = FACTORIES.get("harvester");
        }
        return factory.create(mob, behavior);
    }
}
