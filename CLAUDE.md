# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Excel on a Stick** (`mod.id = excelcomputer`, named Excel Computer before; the id, package and `excelcomputer` folders keep the old name so existing worlds and workbooks still load): a Minecraft mod that opens `.xlsx` / `.csv` workbooks in an in-game spreadsheet screen, using Apache POI. It is one source tree built for many Minecraft versions and loaders with [Stonecutter](https://stonecutter.kikugie.dev/wiki/start/) (forked from the Stonecutter Fabric & NeoForge template).

Targets (declared in `settings.gradle.kts`): `1.20.1` (fabric, forge), `1.21.1`, `1.21.10`, `26.1.2`, `26.2` (fabric, neoforge). Folders under `versions/` that are not in `settings.gradle.kts` (e.g. `1.21.11-*`, `26.3-*`) are leftovers and are not built.

## Commands

Use `./gradlew` (Git Bash) or `gradlew.bat` (PowerShell). Subprojects are named `{mcVersion}-{loader}`.

**Gradle itself must run on Java 25** (Loom 1.18 requires it); the default `JAVA_HOME` here is Java 21 and fails at configuration. Set it first, e.g. `export JAVA_HOME="C:/Users/admin/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2"` (also `C:\Program Files\Java\jdk-25`).

- Build one target: `./gradlew :1.21.10-fabric:build`
- Build every target and copy jars to `build/libs/{mod.version}/`: `./gradlew buildAndCollect` (or `:<project>:buildAndCollect`)
- Run the client: `./gradlew :1.21.10-fabric:runClient` (dev server: `runServer`). Run dirs are `run/{project}` and `run/{project}-server`.
- Switch the active version (rewrites the `//?` comments in `src/` for that target): `./gradlew "Set active project to 1.21.1-neoforge"`, or reset to the VCS version with `./gradlew "Reset active project"`. Do this before editing code for a specific target in the IDE.

There are no tests and no linter. "Verifying" a change means compiling the affected targets, ideally both loaders and the oldest and newest versions it touches.

## Stonecutter: how version/loader differences work

- **Only one copy of the code exists**, in `src/main`. Differences between versions and loaders are written as Stonecutter comments:
  ```java
  //? if >=1.21 {
  return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
  //?} else {
  /*return new ResourceLocation(MOD_ID, path);
  *///?}
  ```
  Branches that are not active are commented out with `/* ... */`. When you add a branch, comment out the inactive one in the same style. Loader constants: `fabric`, `neoforge`, `forge` (from `stonecutter.gradle.kts`).
- **Committed source must stay in the `vcsVersion` state (`1.21.10-fabric`)**, which is also `stonecutter active` in `stonecutter.gradle.kts`. If you switched the active project, reset it before committing.
- `stonecutter.gradle.kts` also does automatic text replacements: `ResourceLocation` → `Identifier` on >=1.21.11, and on >=26.1 `GuiGraphics` → `GuiGraphicsExtractor`, `graphics.drawString`/`drawCenteredString` → `text`/`centeredText`, `super.render(` → `super.extractRenderState(`, `net.minecraft.Util` → `net.minecraft.util.Util`, Fabric `ItemGroupEvents` → `CreativeModeTabEvents`, and on >=26.2 `minecraft.setScreen(` → `minecraft.gui.setScreen(`. Write the old names in source. **Replacements are reversible**: they also run backwards for older targets, so a pattern must be specific enough that its *replacement* text never appears elsewhere (a bare `.text(` → `.drawString(` once broke `change.text()` on <26.1).
- Signature changes can't be expressed as replacements and use `//? if >=26.1` blocks instead: `Screen.render` → `extractRenderState` overrides, and `Screen.resize(Minecraft,int,int)` → `resize(int,int)`.
- Per-version/loader properties (loader versions, Fabric API, compat ranges, `mod.version`) are in `stonecutter.properties.toml`, in tables keyed `["<mc>"]` and `[<loader>."<mc>"]`. `gradle.properties` holds only Gradle JVM options.

## Build scripts (one per loader)

Each version node uses `build.{loader}.gradle.kts`:

- `build.fabric.gradle.kts`: Fabric Loom via `dev.kikugie.loom-back-compat` (handles obfuscated and 26.1+ unobfuscated Minecraft). POI is bundled as jar-in-jar (`include`).
- `build.neoforge.gradle.kts`: ModDevGradle plus Shadow. POI is shaded, and commons-collections4/math3/compress, SparseBitSet and curvesapi are **relocated** to `io.github.reverendbrown.excelcomputer.libs.*` because NeoForge refuses duplicate packages from other mods (e.g. Iris). POI, xmlbeans and the OOXML schema packages cannot be relocated: xmlbeans finds schema classes by names stored in its binary `.xsb` files. The shadow jar is the real mod jar; the plain jar has the `slim` classifier.
- `build.forge.gradle.kts`: ModDevGradle legacyforge for 1.20.1, also shaded.
- `buildSrc/neoforge-mutex`: limits `createMinecraftArtifacts` to one run at a time across versions.

Library version constraints (comments in the build scripts explain why; keep them when editing):
- POI is **5.2.5** on >=1.21 and **5.2.3** (xmlbeans 5.1.1) on 1.20.1. Newer POI needs a newer commons-io than Minecraft ships and crashes with `NoSuchMethodError` outside dev.
- log4j, commons-io and commons-codec are excluded because Minecraft provides them.
- `poi-ooxml-full` is used instead of `poi-ooxml-lite` (the lite jar lacks the autofilter/table XML classes).
- On NeoForge, transitive deps are off, so every needed library is listed explicitly at the version POI declares (e.g. commons-compress 1.25.0).

Resources are shared too. `processResources` in each script handles the differences:
- Only that loader's metadata is kept (`fabric.mod.json`, `META-INF/neoforge.mods.toml`, `META-INF/mods.toml` + `pack.mcmeta` for Forge). The rest are excluded, and `${...}` placeholders are expanded from the properties.
- Recipes are written in the 1.21.2+ format and rewritten by regex for <1.21.2 (ingredient objects) and <1.21 (`item` result key, plural `recipes/` / `loot_tables/` folders).

## Code architecture (`src/main/java/io/github/reverendbrown/excelcomputer`)

- **Entry points per loader**: `ExcelComputerFabric` / `ExcelComputerNeoForge` / `ExcelComputerForge` (common init) and the matching `*Client` classes. Each file is wrapped entirely in `//? if <loader>`. Fabric registers directly in `ModBlocks`/`ModItems.initialize()`. NeoForge/Forge register the same static instances inside `RegisterEvent` and add them to creative tabs through events.
- **Client/server split**: the block (`block/ExcelComputerBlock`) and item (`item/ExcelOnAStickItem`) run on both sides, so they must not reference client classes. They call `ClientHooks.openWorkbookPicker`, a `Runnable` that only the client entry point fills in. A dedicated server never loads screens or POI.
- **Flow**: `WorkbookPickerScreen` lists files from `WorkbookFiles` (`<gameDir>/excelcomputer/workbooks`, `.xlsx` and `.csv`) and opens a `SpreadsheetScreen`.
- `SpreadsheetScreen` (~3700 lines) handles all rendering and input: grid, ribbon tabs (Home/Insert/Draw, laid out in labelled groups), formula bar with the fx button, sheet bar (tabs + column scroll bar, drawn by the screen itself), selection, zoom, resizing, plus the `FormatMenu`, `FilterMenu`, `BorderMenu` and `FunctionMenu` popups. It holds most of the `//? if >=1.21.6` / `>=1.21.9` rendering and input API branches. Opening and saving run on a single background thread (`WORKBOOK_IO`); `tick()` picks up the result, and input is ignored while a save runs because POI workbooks are not thread-safe.
- `WorkbookManager` wraps the POI `Workbook`: cell read/write, formula evaluation (`recalculate`), styles (`CellFormat`, deduplicated through `derivedStyles`), tables/autofilters (`TableInfo`, with raw `CT*` XML beans), column/row sizes, and atomic `save`. It uses only POI and plain Java (no Minecraft classes), so it can be compiled and tested outside the game against a given set of library jars. It has a **CSV mode** (`workbook == null`, `csvRows` instead), so check `csvMode` when adding features.
- `ViewStateStore` saves zoom and scroll position per workbook in `view-state.properties` next to the workbooks folder. It never writes to the workbook files.
- `ColorPalette` holds the UI and Excel color mappings.

## Versioning

Bump `mod.version` in `stonecutter.properties.toml`. Commit messages follow the pattern `1.0.x *short description of feature`.
