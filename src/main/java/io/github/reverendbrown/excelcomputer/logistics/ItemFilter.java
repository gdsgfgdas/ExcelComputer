package io.github.reverendbrown.excelcomputer.logistics;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The Item column of a Transfers row: which items a row moves.
 *
 * <ul>
 *     <li>{@code minecraft:coal}: one item. Without a namespace ({@code coal}) any mod's coal.</li>
 *     <li>{@code #c:ingots}: every item in a tag.</li>
 *     <li>{@code *_ingot}, {@code create:*}: wildcards.</li>
 *     <li>{@code *}, {@code all} or blank: anything.</li>
 *     <li>Several, separated by commas; {@code !} in front leaves items out
 *     ({@code *, !minecraft:dirt}).</li>
 *     <li>Fluids start with {@code fluid:} ({@code fluid:water}, {@code fluid:#c:water},
 *     {@code fluid:*}); the same rules apply, and the row then moves fluids.</li>
 * </ul>
 */
public final class ItemFilter<T> implements Predicate<T> {
    private static final String FLUID = "fluid:";

    private final Registry<T> registry;
    private final ResourceKey<? extends Registry<T>> registryKey;
    private final String kind;
    private final List<Predicate<T>> include = new ArrayList<>();
    private final List<Predicate<T>> exclude = new ArrayList<>();
    private boolean everything;

    private ItemFilter(Registry<T> registry, ResourceKey<? extends Registry<T>> registryKey, String kind) {
        this.registry = registry;
        this.registryKey = registryKey;
        this.kind = kind;
    }

    /** Thrown for a filter that can't be read; the message is shown in the Status column. */
    public static final class BadFilter extends Exception {
        BadFilter(String message) {
            super(message);
        }
    }

    /** Whether an Item cell names fluids ("fluid:..."). */
    public static boolean isFluid(String text) {
        return text.trim().toLowerCase(Locale.ROOT).startsWith(FLUID);
    }

    public static ItemFilter<Item> parse(String text) throws BadFilter {
        return parse(text, new ItemFilter<>(BuiltInRegistries.ITEM, Registries.ITEM, "item"));
    }

    /** A fluid filter; "fluid:" in front of each term is optional. */
    public static ItemFilter<Fluid> parseFluids(String text) throws BadFilter {
        return parse(text, new ItemFilter<>(BuiltInRegistries.FLUID, Registries.FLUID, "fluid"));
    }

    private static <T> ItemFilter<T> parse(String text, ItemFilter<T> filter) throws BadFilter {
        boolean fluids = filter.kind.equals("fluid");

        for (String part : text.split(",")) {
            String term = part.trim().toLowerCase(Locale.ROOT);
            boolean leaveOut = term.startsWith("!");

            if (leaveOut) {
                term = term.substring(1).trim();
            }

            if (fluids && term.startsWith(FLUID)) {
                term = term.substring(FLUID.length()).trim();
            }

            if (term.isEmpty() || term.equals("*") || term.equals("all") || term.equals("any")) {
                if (leaveOut) {
                    throw new BadFilter("\"!" + term + "\" would leave out everything");
                }

                filter.everything = true;
                continue;
            }

            Predicate<T> test = filter.term(term);
            (leaveOut ? filter.exclude : filter.include).add(test);
        }

        // Only exclusions ("!minecraft:dirt"): everything else.
        if (filter.include.isEmpty()) {
            filter.everything = true;
        }

        return filter;
    }

    private Predicate<T> term(String term) throws BadFilter {
        if (term.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(term.substring(1));

            if (id == null) {
                throw new BadFilter("Not a tag: " + term);
            }

            TagKey<T> tag = TagKey.create(registryKey, id);
            return value -> registry.wrapAsHolder(value).is(tag);
        }

        boolean hasNamespace = term.contains(":");

        if (term.contains("*")) {
            Pattern pattern = Pattern.compile(glob(term));
            return value -> pattern.matcher(hasNamespace ? key(value) : path(key(value))).matches();
        }

        if (!hasNamespace) {
            return value -> path(key(value)).equals(term);
        }

        ResourceLocation id = ResourceLocation.tryParse(term);

        if (id == null || !registry.containsKey(id)) {
            throw new BadFilter("Unknown " + kind + ": " + term);
        }

        return value -> key(value).equals(term);
    }

    private static String glob(String term) {
        StringBuilder regex = new StringBuilder();

        for (String piece : term.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }

            regex.append(Pattern.quote(piece));
        }

        return regex.toString();
    }

    /** An item's full id, e.g. "minecraft:coal". */
    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private String key(T value) {
        return registry.getKey(value).toString();
    }

    private static String path(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    @Override
    public boolean test(T value) {
        for (Predicate<T> test : exclude) {
            if (test.test(value)) {
                return false;
            }
        }

        if (everything) {
            return true;
        }

        for (Predicate<T> test : include) {
            if (test.test(value)) {
                return true;
            }
        }

        return false;
    }
}
