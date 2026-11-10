package io.github.reverendbrown.excelcomputer.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The storage mods a hub can be cabled to, and which of them are installed.
 * Each mod's code lives in logistics/compat and is only loaded (by install())
 * when that mod is, so the game never touches the classes of a missing mod.
 *
 * <p>Built in where stonecutter.properties.toml lists the mod for that version
 * and loader (the {@code ae2}, {@code rs} and {@code rs1} Stonecutter constants):
 * Applied Energistics 2 on 1.20.1 (Fabric, Forge), 1.21.1, 26.1.2 and 26.2
 * (NeoForge); Refined Storage 2/3 on 1.21.1 and 26.1.2 (Fabric, NeoForge);
 * Refined Storage 1 on 1.20.1 (Forge).
 */
public final class HubNetworks {
    private static final List<Function<LogisticsHubBlockEntity, NetworkLink>> FACTORIES = new ArrayList<>();
    // NeoForge capability registrations, run when RegisterCapabilitiesEvent fires
    // (the event is passed as Object so this class doesn't need NeoForge).
    private static final List<Consumer<Object>> CAPABILITY_REGISTRARS = new ArrayList<>();
    // Run once the mods are set up (Forge's FMLCommonSetupEvent).
    private static final List<Runnable> SETUP = new ArrayList<>();
    // Makes the hubs' block entities: a subclass when a mod needs the block
    // entity itself to implement its interface (AE2 before 1.21).
    private static BiFunction<BlockPos, BlockState, LogisticsHubBlockEntity> entityFactory =
            LogisticsHubBlockEntity::new;

    private HubNetworks() {
    }

    /** Called once from each loader's common entry point, after the blocks are created. */
    public static void init() {
        //? if rs && fabric {
        /*if (loaded("refinedstorage")) {
            io.github.reverendbrown.excelcomputer.logistics.compat.RsFabric.install();
        }
        *///?}
        //? if rs && neoforge && <1.21.10 {
        /*if (loaded("refinedstorage")) {
            io.github.reverendbrown.excelcomputer.logistics.compat.RsNeoForge.install();
        }
        *///?}
        //? if rs && neoforge && >=1.21.10 {
        /*if (loaded("refinedstorage")) {
            io.github.reverendbrown.excelcomputer.logistics.compat.RsNeoForgeTransfer.install();
        }
        *///?}
        //? if rs1 {
        /*if (loaded("refinedstorage")) {
            io.github.reverendbrown.excelcomputer.logistics.compat.Rs1HubLink.install();
        }
        *///?}
        //? if ae2 && neoforge && <1.21.10 {
        /*if (loaded("ae2")) {
            io.github.reverendbrown.excelcomputer.logistics.compat.Ae2NeoForge.install();
        }
        *///?}
        //? if ae2 && neoforge && >=1.21.10 {
        /*if (loaded("ae2")) {
            io.github.reverendbrown.excelcomputer.logistics.compat.Ae2NeoForgeTransfer.install();
        }
        *///?}
        //? if ae2 && forge {
        /*if (loaded("ae2")) {
            io.github.reverendbrown.excelcomputer.logistics.compat.Ae2Forge.install();
        }
        *///?}
        //? if ae2 && fabric {
        /*if (loaded("ae2")) {
            io.github.reverendbrown.excelcomputer.logistics.compat.Ae2Fabric.install();
        }
        *///?}
    }

    /** For the compat classes: makes every hub join this mod's networks. */
    public static void add(Function<LogisticsHubBlockEntity, NetworkLink> factory) {
        FACTORIES.add(factory);
    }

    /** For the compat classes (NeoForge): run with the RegisterCapabilitiesEvent. */
    public static void addCapabilityRegistrar(Consumer<Object> registrar) {
        CAPABILITY_REGISTRARS.add(registrar);
    }

    /** For the compat classes: run once the mods are set up. */
    public static void addSetup(Runnable setup) {
        SETUP.add(setup);
    }

    /** For the compat classes: the hubs' block entities are made by this instead. */
    public static void setEntityFactory(BiFunction<BlockPos, BlockState, LogisticsHubBlockEntity> factory) {
        entityFactory = factory;
    }

    /** NeoForge's RegisterCapabilitiesEvent fired. */
    public static void onRegisterCapabilities(Object event) {
        for (Consumer<Object> registrar : CAPABILITY_REGISTRARS) {
            registrar.accept(event);
        }
    }

    /** The mods are set up (Forge's FMLCommonSetupEvent). */
    public static void onSetup() {
        for (Runnable setup : SETUP) {
            setup.run();
        }
    }

    /** A new hub block entity (used by the block entity type and the block). */
    public static LogisticsHubBlockEntity newEntity(BlockPos pos, BlockState state) {
        return entityFactory.apply(pos, state);
    }

    /** Whether any storage mod is installed (hubs only tick when one is). */
    static boolean any() {
        return !FACTORIES.isEmpty();
    }

    static List<NetworkLink> create(LogisticsHubBlockEntity hub) {
        if (FACTORIES.isEmpty()) {
            return List.of();
        }

        List<NetworkLink> links = new ArrayList<>(FACTORIES.size());

        for (Function<LogisticsHubBlockEntity, NetworkLink> factory : FACTORIES) {
            links.add(factory.apply(hub));
        }

        return links;
    }

    private static boolean loaded(String mod) {
        //? if fabric {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod);
        //?} elif neoforge {
        /*return net.neoforged.fml.ModList.get().isLoaded(mod);
        *///?} else {
        /*return net.minecraftforge.fml.ModList.get().isLoaded(mod);
        *///?}
    }
}
