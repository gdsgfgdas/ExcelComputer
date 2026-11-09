package io.github.reverendbrown.excelcomputer.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.reverendbrown.excelcomputer.ExcelComputer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The step-by-step guide's content, read from
 * {@code assets/excelcomputer/guide/guide.json}: chapters, each a list of
 * steps (a caption and one or more pictures shown in turn). Pictures are
 * {@code assets/excelcomputer/textures/gui/guide/<image>.png}, any size; a
 * picture that isn't there yet shows its "shot" description instead, so the
 * guide works (and says what to capture) before the art exists.
 *
 * <p>Read again every time the guide opens, so a resource pack or an edited
 * file shows up after F3+T. Client only.
 */
public final class Guide {
    private static final ResourceLocation FILE = ExcelComputer.id("guide/guide.json");
    // Image name to its size in pixels, or null if the picture isn't there.
    private static final Map<String, int[]> SIZES = new HashMap<>();

    public record Frame(String image, String shot) {
        public ResourceLocation texture() {
            return ExcelComputer.id("textures/gui/guide/" + image + ".png");
        }
    }

    public record Step(String text, List<Frame> frames) {
    }

    public record Chapter(String id, String title, List<Step> steps) {
    }

    private Guide() {
    }

    /** The chapters, or a single chapter saying the file couldn't be read. */
    public static List<Chapter> load() {
        SIZES.clear();

        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);

            if (resource.isEmpty()) {
                return problem("The guide file is missing: " + FILE);
            }

            try (Reader reader = resource.get().openAsReader()) {
                return parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (IOException | RuntimeException exception) {
            ExcelComputer.LOGGER.warn("Could not read the guide: {}", exception.toString());
            return problem("The guide could not be read: " + exception.getMessage());
        }
    }

    private static List<Chapter> parse(JsonObject root) {
        List<Chapter> chapters = new ArrayList<>();

        for (JsonElement chapterElement : root.getAsJsonArray("chapters")) {
            JsonObject chapter = chapterElement.getAsJsonObject();
            List<Step> steps = new ArrayList<>();

            for (JsonElement stepElement : chapter.getAsJsonArray("steps")) {
                JsonObject step = stepElement.getAsJsonObject();
                List<Frame> frames = new ArrayList<>();
                JsonArray frameArray = step.has("frames") ? step.getAsJsonArray("frames") : new JsonArray();

                for (JsonElement frameElement : frameArray) {
                    JsonObject frame = frameElement.getAsJsonObject();
                    frames.add(new Frame(frame.get("image").getAsString(),
                            frame.has("shot") ? frame.get("shot").getAsString() : ""));
                }

                steps.add(new Step(step.get("text").getAsString(), List.copyOf(frames)));
            }

            chapters.add(new Chapter(chapter.get("id").getAsString(), chapter.get("title").getAsString(),
                    List.copyOf(steps)));
        }

        return chapters.isEmpty() ? problem("The guide has no chapters") : List.copyOf(chapters);
    }

    private static List<Chapter> problem(String message) {
        return List.of(new Chapter("problem", "Guide", List.of(new Step(message, List.of()))));
    }

    /**
     * The picture's size (width, height) in pixels, read from its PNG header,
     * or null if the picture isn't there yet.
     */
    public static int[] size(Frame frame) {
        // Remembered even when missing (null), so a missing picture isn't looked up every frame.
        if (!SIZES.containsKey(frame.image())) {
            SIZES.put(frame.image(), readSize(frame));
        }

        return SIZES.get(frame.image());
    }

    private static int[] readSize(Frame frame) {
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(frame.texture());

            if (resource.isEmpty()) {
                return null;
            }

            try (InputStream input = resource.get().open()) {
                byte[] header = input.readNBytes(24);

                // PNG signature, then the IHDR chunk: width and height at bytes 16-23.
                if (header.length < 24 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') {
                    return null;
                }

                int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                        | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
                int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                        | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
                return width > 0 && height > 0 ? new int[]{width, height} : null;
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }
}
