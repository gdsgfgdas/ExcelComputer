package io.github.reverendbrown.excelcomputer.item;

import io.github.reverendbrown.excelcomputer.ClientHooks;
import io.github.reverendbrown.excelcomputer.share.ShareProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
//? if >=1.21.2 {
import net.minecraft.world.InteractionResult;
//?} else {
/*import net.minecraft.world.InteractionResultHolder;
*///?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
//? if >=1.21 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
//?}
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?} else {
/*import java.util.List;
*///?}
//? if <1.21 {
/*import org.jetbrains.annotations.Nullable;
*///?}

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A workbook shared through the server. The item only holds the workbook's
 * id, name, owner and expiry time; the file itself is stored in the world's
 * save folder (see ShareServer), so large workbooks never end up in item data.
 */
public class SharedWorkbookItem extends Item {
    private static final String KEY_ID = "excelcomputer_share_id";
    private static final String KEY_NAME = "excelcomputer_share_name";
    private static final String KEY_OWNER = "excelcomputer_share_owner";
    private static final String KEY_EXPIRES = "excelcomputer_share_expires";

    public SharedWorkbookItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(UUID id, String name, String ownerName, long expiresAt) {
        ItemStack stack = new ItemStack(ModItems.SHARED_WORKBOOK);
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ID, id.toString());
        tag.putString(KEY_NAME, name);
        tag.putString(KEY_OWNER, ownerName);
        tag.putLong(KEY_EXPIRES, expiresAt);

        //? if >=1.21 {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.ITEM_NAME, Component.literal(name));
        //?} else {
        /*stack.getOrCreateTag().merge(tag);
        *///?}

        return stack;
    }

    private static CompoundTag tagOf(ItemStack stack) {
        //? if >=1.21 {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
        //?} else {
        /*return stack.getTag();
        *///?}
    }

    private static String readString(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getStringOr(key, "");
        //?} else {
        /*return tag.getString(key);
        *///?}
    }

    private static long readLong(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getLongOr(key, 0L);
        //?} else {
        /*return tag.getLong(key);
        *///?}
    }

    // The shared workbook id, or null for a blank item (e.g. from the creative menu).
    public static UUID getSharedId(ItemStack stack) {
        if (!stack.is(ModItems.SHARED_WORKBOOK)) {
            return null;
        }

        CompoundTag tag = tagOf(stack);

        if (tag == null) {
            return null;
        }

        try {
            return UUID.fromString(readString(tag, KEY_ID));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String getSharedName(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag == null ? "" : readString(tag, KEY_NAME);
    }

    // When the server deletes this workbook (epoch milliseconds), or 0 if unknown.
    public static long getExpiresAt(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag == null ? 0 : readLong(tag, KEY_EXPIRES);
    }

    private static void open(Level level, ItemStack stack) {
        if (!level.isClientSide()) {
            return;
        }

        UUID id = getSharedId(stack);

        if (id != null) {
            ClientHooks.openSharedWorkbook.accept(id, getSharedName(stack));
        }
    }

    //? if >=1.21.2 {
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        open(level, player.getItemInHand(hand));
        return InteractionResult.SUCCESS;
    }
    //?} else {
    /*@Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        open(level, player.getItemInHand(hand));
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
    *///?}

    // Shimmers like an enchanted item, so it stands out from blank Excel Paper (same texture).
    @Override
    public boolean isFoil(ItemStack stack) {
        return getSharedId(stack) != null || super.isFoil(stack);
    }

    // The owner and a live countdown to when the shared workbook expires.
    private static void addDetails(ItemStack stack, Consumer<Component> tooltip) {
        // "Hold [W] for the guide" (see GuideScreen), last.
        addInfo(stack, tooltip);
        ClientHooks.guideTooltip.accept("sharing", tooltip);
    }

    private static void addInfo(ItemStack stack, Consumer<Component> tooltip) {
        CompoundTag tag = tagOf(stack);

        if (tag == null) {
            return;
        }

        String owner = readString(tag, KEY_OWNER);

        if (!owner.isEmpty()) {
            tooltip.accept(Component.literal("Shared by " + owner).withStyle(ChatFormatting.GRAY));
        }

        long expiresAt = readLong(tag, KEY_EXPIRES);

        if (expiresAt > 0) {
            long left = expiresAt - System.currentTimeMillis();
            String text = ShareProtocol.describeExpiry(expiresAt);
            ChatFormatting color = left <= 0
                    ? ChatFormatting.RED
                    : left < 24L * 60 * 60 * 1000 ? ChatFormatting.GOLD : ChatFormatting.GRAY;

            tooltip.accept(Component.literal(Character.toUpperCase(text.charAt(0)) + text.substring(1))
                    .withStyle(color));
        }
    }

    //? if >=1.21.5 {
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        addDetails(stack, tooltip);
    }
    //?} elif >=1.21 {
    /*@Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        addDetails(stack, tooltip::add);
    }
    *///?} else {
    /*// 1.20.1 has no item name component, so the name comes from here.
    @Override
    public Component getName(ItemStack stack) {
        String name = getSharedName(stack);
        return name.isEmpty() ? super.getName(stack) : Component.literal(name);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        addDetails(stack, tooltip::add);
    }
    *///?}
}
