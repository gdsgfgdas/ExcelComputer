package io.github.reverendbrown.excelcomputer.redstone;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
import io.github.reverendbrown.excelcomputer.WorkbookManager;
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.link.LinkedWorkbooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The server side of Redstone Connectors.
 *
 * <p>The linked workbooks are kept by {@link #LINKED}. When something next to
 * a connector changes, the block schedules a tick; the tick writes the
 * incoming signals into the Redstone sheet, recalculates the workbook with
 * POI, and reads the Out column. If an output changed, the neighbors are told.
 *
 * <p>Everything here runs on the server thread.
 */
public final class RedstoneConnectors {
    // A workbook that takes longer than this to recalculate is reported in the log.
    private static final long SLOW_UPDATE_MS = 50;

    // Each connector's outputs (in RedstoneSheet.SIDES order) and the inputs they came from.
    private static final Map<String, int[]> OUTPUTS = new HashMap<>();
    private static final Map<String, int[]> INPUTS = new HashMap<>();

    /** The connectors' linked workbooks, in {@code <world>/excelcomputer/connectors}. */
    public static final LinkedWorkbooks LINKED = new LinkedWorkbooks(
            "connectors", "Redstone Connector",
            state -> ModBlocks.REDSTONE_CONNECTOR != null && state.is(ModBlocks.REDSTONE_CONNECTOR),
            RedstoneSheet::ensure)
            .onStored((level, pos) -> {
                // Runs on the next tick with the new workbook.
                INPUTS.remove(LinkedWorkbooks.key(level, pos));
                level.scheduleTick(pos, ModBlocks.REDSTONE_CONNECTOR, 1);
            })
            .onReset(() -> {
                OUTPUTS.clear();
                INPUTS.clear();
            });

    private RedstoneConnectors() {
    }

    /** The signal a connector sends out of one side. */
    static int signal(ServerLevel level, BlockPos pos, Direction side) {
        LINKED.forServer(level.getServer());
        String key = LinkedWorkbooks.key(level, pos);
        int[] outputs = OUTPUTS.get(key);

        if (outputs == null) {
            // Not worked out since the server started: do it on the next tick.
            if (LINKED.link(level, pos) != null) {
                level.scheduleTick(pos, ModBlocks.REDSTONE_CONNECTOR, 1);
            }

            return 0;
        }

        return outputs[RedstoneSheet.indexOf(side)];
    }

    /** Reads the inputs, runs the sheet, and updates the outputs (on a block tick). */
    static void update(ServerLevel level, BlockPos pos) {
        LINKED.forServer(level.getServer());
        String key = LinkedWorkbooks.key(level, pos);
        int[] inputs = new int[RedstoneSheet.SIDES.length];

        for (int i = 0; i < inputs.length; i++) {
            Direction side = RedstoneSheet.SIDES[i];
            inputs[i] = level.getSignal(pos.relative(side), side);
        }

        int[] previous = OUTPUTS.get(key);

        // Nothing came in differently, so the sheet would give the same answer.
        if (previous != null && Arrays.equals(inputs, INPUTS.get(key))) {
            return;
        }

        INPUTS.put(key, inputs);
        int[] outputs = new int[RedstoneSheet.SIDES.length];
        LinkedWorkbooks.Link link = LINKED.link(level, pos);

        if (link != null) {
            WorkbookManager workbook = LINKED.workbook(link);

            if (workbook != null) {
                long start = System.nanoTime();

                try {
                    int sheet = RedstoneSheet.ensure(workbook);
                    RedstoneSheet.writeInputs(workbook, sheet, inputs);
                    workbook.recalculate();
                    outputs = RedstoneSheet.readOutputs(workbook, sheet);
                } catch (RuntimeException | LinkageError exception) {
                    ExcelComputer.LOGGER.warn("Redstone Connector at {} could not run {}: {}",
                            pos.toShortString(), link.name(), exception.toString());
                }

                long millis = (System.nanoTime() - start) / 1_000_000;

                if (millis > SLOW_UPDATE_MS) {
                    ExcelComputer.LOGGER.warn("Redstone Connector at {} took {} ms to recalculate {}",
                            pos.toShortString(), millis, link.name());
                }
            }
        }

        OUTPUTS.put(key, outputs);

        if (!Arrays.equals(previous, outputs)) {
            //? if >=1.21.2 {
            level.updateNeighborsAt(pos, ModBlocks.REDSTONE_CONNECTOR, null);
            //?} else {
            /*level.updateNeighborsAt(pos, ModBlocks.REDSTONE_CONNECTOR);
            *///?}
        }
    }

    /** The connector was broken: forget its link and delete its workbook. */
    static void remove(ServerLevel level, BlockPos pos) {
        LINKED.forServer(level.getServer());
        String key = LinkedWorkbooks.key(level, pos);
        OUTPUTS.remove(key);
        INPUTS.remove(key);
        LINKED.remove(level, pos);
    }
}
