plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.10-fabric"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    val (version, loader) = current.project.split('-', limit = 2)

    // Makes version- and loader-specific properties apply from `stoncutter.properties.toml`
    properties {
        tags(version, loader)
    }

    // Adds constants to Stonecutter comments (i.e. for `//? if fabric {...`)
    constants {
        match(loader, "fabric", "neoforge", "forge")
    }

    swaps["mod_version"] = "\"${properties.get<String>("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
    constants["release"] = properties.get<String>("mod.id") != "template"
    dependencies["fapi"] = properties.getOrNull<String>("deps.fabric_api") ?: "0"

    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }

        string(current.parsed >= "26.1") {
            replace("classTweaker v2 named", "classTweaker v2 official")
            replace("GuiGraphics", "GuiGraphicsExtractor")
            // 26.1 renamed the text drawing methods and Screen.render.
            // These also run in reverse on older versions, so keep the patterns
            // specific ("graphics." prefix), or unrelated calls like change.text() break.
            replace("graphics.drawString(", "graphics.text(")
            replace("graphics.drawCenteredString(", "graphics.centeredText(")
            replace("super.render(", "super.extractRenderState(")
            replace("import net.minecraft.Util;", "import net.minecraft.util.Util;")
            // Fabric API replaced the item group API with the creative tab API
            replace("net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents", "net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents")
            replace("ItemGroupEvents.modifyEntriesEvent(", "CreativeModeTabEvents.modifyOutputEvent(")
        }

        string(current.parsed >= "26.2") {
            // 26.2 moved Minecraft.setScreen to Minecraft.gui.setScreen.
            replace("minecraft.setScreen(", "minecraft.gui.setScreen(")
            replace("Minecraft.getInstance().setScreen(", "Minecraft.getInstance().gui.setScreen(")
        }
    }
}
