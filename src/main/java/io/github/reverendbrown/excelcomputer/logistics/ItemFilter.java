package io.github.reverendbrown.excelcomputer.logistics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

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
 * </ul>
 */
public final class ItemFilter implements Predicate<Item> {
    private final List<Predicate<Item>> include = new ArrayList<>();
    private final List<Predicate<Item>> exclude = new ArrayList<>();
    private boolean everything;

    private ItemFilter() {
    }

    /** Thrown for a filter that can't be read; the message is shown in the Status column. */
    public static final class BadFilter extends Exception {
        BadFilter(String message) {
            super(message);
        }
    }

    public static ItemFilter parse(String text) throws BadFilter {
        ItemFilter filter = new ItemFilter();

        for (String part : text.split(",")) {
            String term = part.trim().toLowerCase(Locale.ROOT);
            boolean leaveOut = term.startsWith("!");

            if (leaveOut) {
                term = term.substring(1).trim();
            }

            if (term.isEmpty() || term.equals("*") || term.equals("all") || term.equals("any")) {
                if (leaveOut) {
                    throw new BadFilter("\"!" + term + "\" would leave out everything");
                }

                filter.everything = true;
                continue;
            }

            Predicate<Item> test = term(term);
            (leaveOut ? filter.exclude : filter.include).add(test);
        }

        // Only exclusions ("!minecraft:dirt"): everything else.
        if (filter.include.isEmpty()) {
            filter.everything = true;
        }

        return filter;
    }

    private static Predicate<Item> term(String term) throws BadFilter {
        if (term.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(term.substring(1));

            if (id == null) {
                throw new BadFilter("Not a tag: " + term);
            }

            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            return item -> BuiltInRegistries.ITEM.wrapAsHolder(item).is(tag);
        }

        boolean hasNamespace = term.contains(":");

        if (term.contains("*")) {
            Pattern pattern = Pattern.compile(glob(term));
            return item -> pattern.matcher(hasNamespace ? id(item) : path(item)).matches();
        }

        if (!hasNamespace) {
            return item -> path(item).equals(term);
        }

        ResourceLocation id = ResourceLocation.tryParse(term);

        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new BadFilter("Unknown item: " + term);
        }

        return item -> id(item).equals(term);
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

    private static String path(Item item) {
        String id = id(item);
        return id.substring(id.indexOf(':') + 1);
    }

    @Override
    public boolean test(Item item) {
        for (Predicate<Item> test : exclude) {
            if (test.test(item)) {
                return false;
            }
        }

        if (everything) {
            return true;
        }

        for (Predicate<Item> test : include) {
            if (test.test(item)) {
                return true;
            }
        }

        return false;
    }
}
