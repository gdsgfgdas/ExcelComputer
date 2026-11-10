package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if ae2 && !neoforge {
/*import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

// AE2 before 1.21 (Forge, Fabric) finds devices by checking whether the block
// entity itself is an IInWorldGridNodeHost, so with AE2 installed the hubs get
// this subclass (see Ae2Forge / Ae2Fabric). It hands AE2 the hub's Ae2HubLink node.
public class Ae2HubBlockEntity extends LogisticsHubBlockEntity implements IInWorldGridNodeHost {
    public Ae2HubBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public IGridNode getGridNode(Direction side) {
        Ae2HubLink link = link(Ae2HubLink.class);
        return link == null ? null : link.getGridNode(side);
    }
}
*///?}
