package io.github.reverendbrown.excelcomputer.guide;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?}

import java.util.List;
import java.util.function.Consumer;

/**
 * The step-by-step guide (see {@link Guide} for the content): the chapters on
 * the left; the current page (one picture and its step's caption) and Back /
 * Next on the right. A step with several pictures has one page per picture.
 *
 * <p>Opened from the workbook picker's Guide button, or by holding W over an
 * Excel item's tooltip (see {@link #tooltip}), which opens the chapter about
 * that item. Closing it goes back to the screen it was opened from.
 */
public class GuideScreen extends Screen {
    // Holding W this long over an item opens its chapter.
    private static final long HOLD_MILLIS = 700;
    private static final int MARGIN = 10;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;
    private final List<Guide.Chapter> chapters;
    private int chapter;
    private int step;
    // Which of the step's pictures is showing.
    private int frame;

    // Which item's chapter W is being held for, and since when (0: not held).
    private static String holdingFor;
    private static long holdingSince;

    public GuideScreen(Screen parent, String chapterId) {
        super(Component.literal("Excel on a Stick Guide"));
        this.parent = parent;
        this.chapters = Guide.load();

        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).id().equals(chapterId)) {
                chapter = i;
            }
        }
    }

    // ----- holding W over an item's tooltip -----

    /**
     * Adds "Hold [W] for the guide" to an item's tooltip; holding W for a
     * moment opens the guide at the chapter about that item. Called every
     * frame the tooltip is shown (through ClientHooks.guideTooltip).
     */
    public static void tooltip(String chapterId, Consumer<Component> lines) {
        Minecraft minecraft = Minecraft.getInstance();
        //? if >=1.21.9 {
        long window = minecraft.getWindow().handle();
        //?} else {
        /*long window = minecraft.getWindow().getWindow();
        *///?}
        boolean held = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS;
        long now = System.currentTimeMillis();

        if (!held) {
            holdingFor = null;
            holdingSince = 0;
            lines.accept(Component.literal("Hold [W] for the guide").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        if (!chapterId.equals(holdingFor)) {
            holdingFor = chapterId;
            holdingSince = now;
        }

        long heldFor = now - holdingSince;
        int filled = (int) Math.min(10, heldFor * 10 / HOLD_MILLIS);
        lines.accept(Component.literal("Hold [W] for the guide ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("|".repeat(filled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("|".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY)));

        if (heldFor >= HOLD_MILLIS) {
            holdingFor = null;
            holdingSince = 0;
            //? if >=26.2 {
            /*Screen current = minecraft.gui.screen();
            *///?} else {
            Screen current = minecraft.screen;
            //?}
            // After this frame: the tooltip is drawn in the middle of the current screen's render.
            //? if >=1.21.9 {
            minecraft.schedule(() -> minecraft.setScreen(new GuideScreen(current, chapterId)));
            //?} else {
            /*minecraft.tell(() -> minecraft.setScreen(new GuideScreen(current, chapterId)));
            *///?}
        }
    }

    // ----- layout -----

    private int sidebarWidth() {
        return Math.max(80, Math.min(130, this.width / 4));
    }

    private int contentLeft() {
        return MARGIN + sidebarWidth() + MARGIN;
    }

    private int contentRight() {
        return this.width - MARGIN;
    }

    private Guide.Step currentStep() {
        List<Guide.Step> steps = chapters.get(chapter).steps();
        return steps.isEmpty() ? null : steps.get(Math.min(step, steps.size() - 1));
    }

    // Pages in a step: one per picture, or one for a step without pictures.
    private int pagesIn(int chapterIndex, int stepIndex) {
        return Math.max(1, chapters.get(chapterIndex).steps().get(stepIndex).frames().size());
    }

    private int lastPage(int chapterIndex, int stepIndex) {
        return pagesIn(chapterIndex, stepIndex) - 1;
    }

    private boolean onLastPageOfChapter() {
        int steps = chapters.get(chapter).steps().size();
        return step + 1 >= steps && (steps == 0 || frame >= lastPage(chapter, step));
    }

    // The current page's number in its chapter, and how many pages the chapter has.
    private int[] pageOfChapter() {
        List<Guide.Step> steps = chapters.get(chapter).steps();
        int page = 0;
        int total = 0;

        for (int i = 0; i < steps.size(); i++) {
            if (i == step) {
                page = total + frame + 1;
            }
            total += pagesIn(chapter, i);
        }

        return new int[] {page, total};
    }

    @Override
    protected void init() {
        int sidebar = sidebarWidth();
        int y = 30;

        for (int i = 0; i < chapters.size(); i++) {
            int index = i;
            Button button = Button.builder(Component.literal(chapters.get(i).title()), b -> show(index, 0, 0))
                    .bounds(MARGIN, y, sidebar, BUTTON_HEIGHT)
                    .build();
            button.active = i != chapter;
            this.addRenderableWidget(button);
            y += BUTTON_HEIGHT + 2;
        }

        int bottom = this.height - MARGIN - BUTTON_HEIGHT;
        int left = contentLeft();
        int right = contentRight();
        boolean lastPage = onLastPageOfChapter();

        Button back = Button.builder(Component.literal("< Back"), b -> previous())
                .bounds(left, bottom, 60, BUTTON_HEIGHT)
                .build();
        back.active = frame > 0 || step > 0 || chapter > 0;
        this.addRenderableWidget(back);

        Button next = Button.builder(Component.literal(lastPage ? "Next chapter >" : "Next >"), b -> next())
                .bounds(right - 150, bottom, 90, BUTTON_HEIGHT)
                .build();
        next.active = !lastPage || chapter + 1 < chapters.size();
        this.addRenderableWidget(next);

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(right - 56, bottom, 56, BUTTON_HEIGHT)
                .build());
    }

    private void show(int chapterIndex, int stepIndex, int frameIndex) {
        chapter = chapterIndex;
        step = stepIndex;
        frame = frameIndex;
        this.clearWidgets();
        this.init();
    }

    private void next() {
        int steps = chapters.get(chapter).steps().size();

        if (steps > 0 && frame < lastPage(chapter, step)) {
            show(chapter, step, frame + 1);
        } else if (step + 1 < steps) {
            show(chapter, step + 1, 0);
        } else if (chapter + 1 < chapters.size()) {
            show(chapter + 1, 0, 0);
        }
    }

    private void previous() {
        if (frame > 0) {
            show(chapter, step, frame - 1);
        } else if (step > 0) {
            show(chapter, step - 1, lastPage(chapter, step - 1));
        } else if (chapter > 0) {
            int steps = chapters.get(chapter - 1).steps().size();
            int lastStep = Math.max(0, steps - 1);
            show(chapter - 1, lastStep, steps == 0 ? 0 : lastPage(chapter - 1, lastStep));
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    // ----- drawing -----

    @Override
    //? if >=26.1 {
    /*public void extractRenderState(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    *///?} else {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        //? if <1.20.2 {
        /*this.renderBackground(graphics);
        *///?}
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = contentLeft();
        int right = contentRight();
        int width = right - left;
        Guide.Chapter current = chapters.get(chapter);
        Guide.Step shown = currentStep();

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        if (shown == null) {
            return;
        }

        // Caption at the bottom, above the buttons; the picture fills the space above it.
        List<FormattedCharSequence> caption = this.font.split(Component.literal(shown.text()), width);
        int buttonsTop = this.height - MARGIN - BUTTON_HEIGHT;
        int captionTop = buttonsTop - 6 - caption.size() * 10;
        int heading = 30;

        int[] page = pageOfChapter();
        graphics.drawString(this.font, current.title() + "  (page " + page[0] + " of " + page[1] + ")",
                left, heading, 0xFFB8DDBF);

        int pictureTop = heading + 14;
        int pictureBottom = captionTop - 6;
        drawPicture(graphics, shown, left, pictureTop, right, pictureBottom);

        for (int i = 0; i < caption.size(); i++) {
            graphics.drawString(this.font, caption.get(i), left, captionTop + i * 10, 0xFFFFFFFF);
        }
    }

    // The page's picture, 90% as big as fits (keeping its shape), centered in the box.
    private void drawPicture(GuiGraphics graphics, Guide.Step shown, int left, int top, int right, int bottom) {
        if (shown.frames().isEmpty() || bottom - top < 20) {
            return;
        }

        int count = shown.frames().size();
        int index = Math.min(this.frame, count - 1);
        Guide.Frame frame = shown.frames().get(index);
        int[] size = Guide.size(frame);
        // A missing picture keeps a 16:9 box for its description.
        int imageWidth = size != null ? size[0] : 16;
        int imageHeight = size != null ? size[1] : 9;
        // 90% of the space it could fill, so it doesn't crowd the screen.
        double scale = 0.9 * Math.min((right - left) / (double) imageWidth, (bottom - top) / (double) imageHeight);
        int drawWidth = Math.max(1, (int) (imageWidth * scale));
        int drawHeight = Math.max(1, (int) (imageHeight * scale));
        int x = left + (right - left - drawWidth) / 2;
        int y = top + (bottom - top - drawHeight) / 2;

        if (size != null) {
            //? if >=1.21.6 {
            graphics.blit(RenderPipelines.GUI_TEXTURED, frame.texture(), x, y, 0, 0, drawWidth, drawHeight,
                    size[0], size[1], size[0], size[1]);
            //?} else {
            /*graphics.blit(frame.texture(), x, y, drawWidth, drawHeight, 0, 0, size[0], size[1], size[0], size[1]);
            *///?}
        } else {
            drawPlaceholder(graphics, frame, x, y, drawWidth, drawHeight);
        }

        // Dots under the picture: which of the step's pictures is showing.
        if (count > 1) {
            int dotsLeft = left + (right - left) / 2 - count * 4;

            for (int i = 0; i < count; i++) {
                graphics.fill(dotsLeft + i * 8, bottom + 1, dotsLeft + i * 8 + 4, bottom + 4,
                        i == index ? 0xFFB8DDBF : 0xFF555555);
            }
        }
    }

    // What to capture for a picture that doesn't exist yet.
    private void drawPlaceholder(GuiGraphics graphics, Guide.Frame frame, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF555555);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF262626);

        int textWidth = Math.max(40, width - 16);
        List<FormattedCharSequence> lines = new java.util.ArrayList<>();
        lines.addAll(this.font.split(Component.literal("Picture needed: " + frame.image() + ".png")
                .withStyle(ChatFormatting.YELLOW), textWidth));
        lines.addAll(this.font.split(Component.literal(frame.shot()).withStyle(ChatFormatting.GRAY), textWidth));
        int textTop = y + Math.max(4, (height - lines.size() * 10) / 2);

        for (int i = 0; i < lines.size() && textTop + i * 10 + 9 <= y + height; i++) {
            graphics.drawString(this.font, lines.get(i), x + 8, textTop + i * 10, 0xFFFFFFFF);
        }
    }
}
