# Changelog

All notable changes to Excel on a Stick (formerly Excel Computer).

Supported versions: Minecraft 1.20.1 (Fabric, Forge), 1.21.1, 1.21.10, 26.1.2 and 26.2 (Fabric, NeoForge).

## 2.0.1 - Draw update (2026-11-10)

### Added
- **Draw tab**: pen, highlighter and eraser on the sheet, with stroke color and thickness, and Clear to erase every drawing on a sheet. Drawings stay on the cells they were drawn over, are saved in the .xlsx as shapes (Excel shows them too) and can be undone.
- **Sort tables**: Sort Smallest to Largest / Largest to Smallest (A to Z / Z to A for text) from a column's filter dropdown, the right-click menu or Insert > Sort & Search. Whole rows move together, filters are applied again afterwards, and the whole sort is one undo step. Total rows at the bottom of a table stay in place.
- **Search tables**: Ctrl+F (or Search Table / the Search button) opens a search bar that shows only the rows of the selected table containing the text, with a match count. Enter / Shift+Enter jump between matching cells, Esc shows every row again. Searching only changes the view and is never saved into the file.
- **Fluids for Logistics Hubs**: tanks register like inventories, and Transfers rows move fluids with `fluid:` filters (`fluid:water`, `fluid:*`) in millibuckets. Each tier has a fluid limit per cycle (Copper 1, Iron 4, Diamond 16, Netherite 64 buckets).
- **Applied Energistics 2 and Refined Storage**: a cable touching a hub joins it to the network, which becomes an extra row in Inventories (`ME Network` / `RS Network`) usable in Transfers. Stock lists its most common items (`hubNetworkStockRows` in the server config). Works wherever those mods exist: AE2 on 1.20.1, 1.21.1, 26.1.2 and 26.2; Refined Storage on 1.20.1 (Forge), 1.21.1 and 26.1.2.
- **Storage controllers** (such as Sophisticated Storage's) add their whole storage network to a hub as one inventory.

### Changed
- Updated guide pictures and text (crafting the stick, registering tanks and storage networks, fluid transfers).
- The right-click menu is wider to fit the new sort and search options.

### Fixed
- Sorting no longer breaks SUM totals: moved formulas only update references to their own row.

## 2.0.0 - Logistics update (2026-11-08)

### Added
- **Logistics Hubs** in four tiers (Copper, Iron, Diamond, Netherite), each with its own range, items per cycle, speed and inventory limit; Netherite also reaches other dimensions.
- **Hub Upgrades** (iron, diamond, netherite) turn a hub into the next tier in place.
- Register chests, furnaces, barrels and modded storage by sneak-right-clicking a side with Excel on a Stick. The hub's workbook has Inventories, Stock and Transfers sheets; transfers use item ids, tags (`#c:ingots`) and wildcards (`*_ingot`).
- Open and save any of your hubs from anywhere with the stick; a list of hubs, renaming, and an Access panel to share a hub with other players (one editor at a time).
- Server limits for hubs (workbook size, cells, formulas, hubs per player, cycle time budget) in `config/excelcomputer-server.properties`.
- A step-by-step picture guide: the picker's Guide button, the Excel Guide book, or hold W over an Excel item's tooltip.

### Changed
- New Excel Table textures.
- Smaller jar: unused parts of Apache POI (Word, PowerPoint, Visio, signatures) are left out.

## 1.0.6.6 (2026-11-05)

### Added
- Formula autocomplete.
- Conditional formatting: highlight rules, data bars and color scales.
- Vertical alignment.
- The Home ribbon collapses groups into dropdowns when the window is narrow.

### Changed
- Shorter vertical alignment labels.

## 1.0.6.5 (2026-11-03)

### Added
- Shared workbooks.
- Right-click menu and mini toolbar on cells.
- Windowed spreadsheet (the game stays visible around it).

## 1.0.6 (2026-11-02)

### Changed
- Renamed to **Excel on a Stick**.
- UI overhaul and faster loading of workbooks.
- Copy and paste buttons.
- Compatibility updates, including Minecraft 26.2.

### Fixed
- Forge 1.20.1 crash on launch.

## 1.0.5 (2026-10-27 to 2026-11-02)

### Added
- Minecraft 1.20.1 and 26.1 support.
- Open Folder button for the workbooks folder.

## 1.0.4 (2026-10-24)

### Added
- Tables and filters.
- Improved .csv support.

## 1.0.3 (2026-10-20 to 2026-10-21)

### Added
- Reading .csv files.
- Dedicated server compatibility.
- GUI size override.
- Number formatting.

### Fixed
- Number formatting not working in formulas.

## 1.0.2 (2026-10-18)

### Added
- Cell formatting and the Home tab.

## 1.0.0 (2026-10-16)

- First version: open .xlsx workbooks in an in-game spreadsheet.
