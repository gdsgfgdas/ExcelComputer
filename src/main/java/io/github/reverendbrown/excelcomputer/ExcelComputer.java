package io.github.reverendbrown.excelcomputer;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Shared by every loader. The loader-specific entry points are
// ExcelComputerFabric and ExcelComputerNeoForge.
public class ExcelComputer {
	public static final String MOD_ID = "excelcomputer";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// The Redstone Connector is hidden until it is finished: when false its block
	// and item are never created or registered, and the build scripts leave out
	// its resources (they read this line, so keep it as one plain true/false).
	public static final boolean REDSTONE_CONNECTOR = false;

	public static ResourceLocation id(String path) {
		//? if >=1.21 {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
		//?} else {
		/*return new ResourceLocation(MOD_ID, path);
		*///?}
	}
}
