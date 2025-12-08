package walksy.clientkits.manager;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.EntityEquipment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import walksy.clientkits.main.ClientKitsMod;

import java.util.List;


public class KitCommandManager {
    public static boolean loadKit = false, shouldChangeBack = false;
    private static GameMode oldGM = null;
    private static String tempName = null;
    public static int i = 0;
    private static CommandContext<FabricClientCommandSource> tempSource = null;

    public KitCommandManager() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("ck")
                .then(ClientCommandManager.literal("save")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            handleSaveCommand(context, StringArgumentType.getString(context, "name"));
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("load")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(KitManager.kits.keySet(), builder))
                        .executes(context -> {
                            loadKit = true;
                            tempName = StringArgumentType.getString(context, "name");
                            tempSource = context;
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("delete")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(KitManager.kits.keySet(), builder))
                        .executes(context -> {
                            handleDeleteCommand(StringArgumentType.getString(context, "name"));
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("preview")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(KitManager.kits.keySet(), builder))
                        .executes(context -> {
                            handlePreviewCommand(StringArgumentType.getString(context, "name"));
                            return 1;
                        })
                    )
                )
            )
        );
    }


    void handleSaveCommand(CommandContext<FabricClientCommandSource> source, String name) {
        NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, source.getSource().getRegistryManager());
        WriteView.ListAppender<StackWithSlot> list = view.getListAppender(name, StackWithSlot.CODEC);
        PlayerInventory inventory = source.getSource().getPlayer().getInventory();
        inventory.writeData(list);
        writeEquipment(list, inventory);
        KitManager.kits.put(name, view.getNbt());
        ConfigManager.saveKitToFile(name);
        ClientKitsMod.debugMessage("§aSaved kit: " + name);
    }

    void handleDeleteCommand(String name) {
        if (KitManager.kits.get(name) != null) {
            KitManager.kits.remove(name);
            ClientKitsMod.debugMessage("§aDeleted Kit: " + name + ".");
            ConfigManager.deleteKit(name);
        } else {
            ClientKitsMod.debugMessage("§cCannot find the kit '" + name + "' to delete.");
        }
    }

    private static void writeEquipment(WriteView.ListAppender<StackWithSlot> list, PlayerInventory inventory) {
        for(Integer equipmentSlot : PlayerInventory.EQUIPMENT_SLOTS.keySet()) {
            list.add(new StackWithSlot(equipmentSlot, inventory.getStack(equipmentSlot)));
        }
    }

    private static void readInventory(ReadView.TypedListReadView<StackWithSlot> list, Inventory inventory) {
        inventory.clear();

        for(StackWithSlot slot : list) {
            inventory.setStack(slot.slot(), slot.stack());
        }
    }

    private static ReadView.TypedListReadView<StackWithSlot> getKitTypedListView(PlayerEntity player, String name) {
        return NbtReadView.create(ErrorReporter.EMPTY, player.getRegistryManager(), KitManager.kits.get(name)).getTypedListView(name, StackWithSlot.CODEC);
    }

    void handlePreviewCommand(String name)
    {
        if (KitManager.kits.get(name) != null) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            PlayerInventory tempInv = new PlayerInventory(player, new EntityEquipment());
            readInventory(getKitTypedListView(player, name), tempInv);
            MinecraftClient.getInstance().send(() -> MinecraftClient.getInstance().setScreen(new PreviewScreen(new PlayerScreenHandler(tempInv, true, MinecraftClient.getInstance().player), tempInv, name)));
        } else {
            ClientKitsMod.debugMessage("§cCannot find the kit '" + name + "' to preview.");
        }
    }

    public static void tick() {
        if (loadKit) {
            if (!tempSource.getSource().getPlayer().getAbilities().creativeMode) {
                if (tempSource.getSource().getPlayer().hasPermissionLevel(2) || tempSource.getSource().getPlayer().hasPermissionLevel(4)) {
                    PlayerListEntry playerListEntry = tempSource.getSource().getClient().getNetworkHandler().getPlayerListEntry(tempSource.getSource().getPlayer().getUuid());
                    oldGM = playerListEntry.getGameMode();
                    tempSource.getSource().getPlayer().networkHandler.sendChatCommand("gamemode creative");
                    shouldChangeBack = true;
                }
                if (!shouldChangeBack) {
                    ClientKitsMod.debugMessage("§cMust be in creative mode to receive kit.");
                    reset();
                    return;
                }
            }
            if (!tempSource.getSource().getPlayer().getAbilities().creativeMode) return;
            NbtCompound kit = KitManager.kits.get(tempName);
            if (kit == null) {
                ClientKitsMod.debugMessage("§cKit not found.");
                reset();
                return;
            }
            PlayerEntity player = tempSource.getSource().getPlayer();
            PlayerInventory tempInv = new PlayerInventory(player, new EntityEquipment());
            readInventory(getKitTypedListView(player, tempName), tempInv);
            List<Slot> slots = tempSource.getSource().getPlayer().playerScreenHandler.slots;
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).inventory == tempSource.getSource().getPlayer().getInventory()) {
                    ItemStack existingItemStack = tempSource.getSource().getPlayer().getInventory().getStack(slots.get(i).getIndex());
                    if (!existingItemStack.isEmpty()) {
                        player.playerScreenHandler.getSlot(i).setStackNoCallbacks(ItemStack.EMPTY);
                        tempSource.getSource().getClient().interactionManager.clickCreativeStack(ItemStack.EMPTY, i); //clear out old items
                    }
                    ItemStack itemStack = tempInv.getStack(slots.get(i).getIndex());
                    if (!itemStack.isEmpty()) {
                        player.playerScreenHandler.getSlot(i).setStack(itemStack);
                        tempSource.getSource().getClient().interactionManager.clickCreativeStack(itemStack, i);
                    }
                }
            }
            if (shouldChangeBack) {
                String command = switch (oldGM) {
                    case SURVIVAL -> "survival";
                    case ADVENTURE -> "adventure";
                    case SPECTATOR -> "spectator";
                    default -> "";
                };
                tempSource.getSource().getPlayer().networkHandler.sendChatCommand("gamemode " + command);
                shouldChangeBack = false;
            }

            tempSource.getSource().getPlayer().playerScreenHandler.sendContentUpdates();
            ClientKitsMod.debugMessage("§aLoaded kit: " + tempName + ".");
            reset();
        }
        if (i != 0)
        {
            i--;
        }
    }

    static void reset() {
        loadKit = false;
        shouldChangeBack = false;
        tempName = null;
        tempSource = null;
        oldGM = null;
        i = 3;
    }


    static class PreviewScreen extends HandledScreen<PlayerScreenHandler> {
        public PreviewScreen(PlayerScreenHandler playerScreenHandler, PlayerInventory inventory, String name) {
            super(playerScreenHandler, inventory, Text.literal(name).styled(style -> style.withColor(Formatting.BOLD)));
            this.titleX = 80;
        }

        @Override
        protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
            context.drawText(this.textRenderer, this.title, this.titleX, this.titleY, 0x404040, false);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            this.renderBackground(context, mouseX, mouseY, delta);
            super.render(context, mouseX, mouseY, delta);
            this.drawMouseoverTooltip(context, mouseX, mouseY);
        }

        @Override
        protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
            int i = this.x;
            int j = this.y;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, this.x, this.y, 0, 0, this.backgroundWidth, this.backgroundHeight, 256, 256);
            InventoryScreen.drawEntity(context, i + 26, j + 8, i + 75, j + 78, 30, 0.0625F, mouseX, mouseY, this.client.player);
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            return false;
        }
    }
}


