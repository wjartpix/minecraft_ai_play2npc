package com.goodbird.player2npc.companion;

import adris.altoclef.AltoClefController;
import adris.altoclef.player2api.Character;
import adris.altoclef.player2api.manager.ConversationManager;
import adris.altoclef.player2api.utils.CharacterUtils;
import baritone.api.IBaritone;
import baritone.api.entity.IAutomatone;
import baritone.api.entity.IHungerManagerProvider;
import baritone.api.entity.IInteractionManagerProvider;
import baritone.api.entity.IInventoryProvider;
import baritone.api.entity.LivingEntityHungerManager;
import baritone.api.entity.LivingEntityInteractionManager;
import baritone.api.entity.LivingEntityInventory;
import com.goodbird.player2npc.Player2NPC;
import com.goodbird.player2npc.network.AutomatonSpawnPacket;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

/**
 * We implement:
 * - IAutomatone for this entity to be counted as an automatone (used for
 * tracking nearby automatons and such)
 * - IInventoryProvider for this entity to have a player-like inventory
 * - IInteractionManagerProvider for this entity to have a player-like
 * interaction manager (for breaking and placing blocks and using items)
 * - IHungerManagerProvider for this entity to have a hunger manager
 */
public class AutomatoneEntity extends LivingEntity
        implements IAutomatone, IInventoryProvider, IInteractionManagerProvider, IHungerManagerProvider {
    // Fields to store the provided instances of inventory and such
    public LivingEntityInteractionManager manager;
    public LivingEntityInventory inventory;
    public LivingEntityHungerManager hungerManager;

    // Main automatone controller
    public AltoClefController controller;

    // Player2 Character
    public Character character;

    // An identifier of a loading texture (used in rendering)
    public ResourceLocation textureLocation;

    // Previous motion (used in rendering)
    protected Vec3 lastVelocity;

    // A final field for defining your game id
    private final String PLAYER2_GAME_ID = "player2-ai-npc-minecraft";

    // Standard constructor for entity registering
    public AutomatoneEntity(EntityType<? extends AutomatoneEntity> type, Level world) {
        super(type, world);
        init();
    }

    public void init() {
        // We set its speed and step height
        this.setMaxUpStep(0.6f);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.4f);
        // Initialize the provided managers and such
        manager = new LivingEntityInteractionManager(this);
        inventory = new LivingEntityInventory(this);
        hungerManager = new LivingEntityHungerManager();
        // We initialize the altoclef controller ONLY ON CLIENT SIDE!
        if (!level().isClientSide && character != null) {
            this.controller = new AltoClefController(IBaritone.KEY.get(this), character, PLAYER2_GAME_ID);
            ConversationManager.sendGreeting(this.controller, character);
        }
    }

    // Constructor for the manual entity creation
    public AutomatoneEntity(Level world, Character character, Player owner) {
        super(Player2NPC.AUTOMATONE, world);
        setCharacter(character); // If we got a character, we store it
        init();
        this.controller.setOwner(owner);
    }

    // Interface implementation (just make the getters for the managers and the
    // inventory)
    @Override
    public LivingEntityInventory getLivingInventory() {
        return inventory;
    }

    @Override
    public LivingEntityInteractionManager getInteractionManager() {
        return manager;
    }

    @Override
    public LivingEntityHungerManager getHungerManager() {
        return hungerManager;
    }

    // We implement NBT read and write methods
    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("head_yaw")) {
            this.yHeadRot = tag.getFloat("head_yaw");
        }
        ListTag nbtList = tag.getList("Inventory", 10);
        this.inventory.readNbt(nbtList);
        this.inventory.selectedSlot = tag.getInt("SelectedItemSlot");
        if (character == null && tag.contains("character")) { // If we have a character stored, we read it and
                                                              // initialize the controller with it
            CompoundTag compound = tag.getCompound("character");
            character = CharacterUtils.readFromNBT(compound);
            if (controller == null) {
                controller = new AltoClefController(IBaritone.KEY.get(this), character, PLAYER2_GAME_ID);
            }
            ConversationManager.sendGreeting(controller, character);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("head_yaw", this.yHeadRot);
        tag.put("Inventory", this.inventory.writeNbt(new ListTag()));
        tag.putInt("SelectedItemSlot", this.inventory.selectedSlot);
        if (character != null) {
            CompoundTag compound = new CompoundTag();
            CharacterUtils.writeToNBT(compound, character);
            tag.put("character", compound);
        }
    }

    // We tick all the managers and stuff
    @Override
    public void tick() {
        this.lastVelocity = this.getDeltaMovement(); // Setting prev velocity for rendering
        manager.update();
        inventory.updateItems();
        // hungerManager.update(this); //if you want your automatone to feel hunger -
        // you need to uncomment that
        noActionTime++; // Tick this for the NPC to attack (LivingEntities don't do that by default)
        if (!this.level().isClientSide) // We tick the controller only on server side
            controller.serverTick();
        super.tick();
        updateSwingTime(); // For arm swing rendering
    }

    // We tweak motion a little bit
    @Override
    public void aiStep() {
        if (this.isInWater() && this.isShiftKeyDown()) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0, -0.04, 0.0));
        }
        super.aiStep();
        this.yHeadRot = this.getYRot();
        pickupItems(); // And tick the item pickup
    }

    // Pickup all the items in range of 3 blocks
    public void pickupItems() {
        if (!this.level().isClientSide && this.isAlive() && !this.dead
                && this.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            Vec3i vec3i = new Vec3i(3, 3, 3);
            for (ItemEntity itemEntity : this.level().getEntitiesOfClass(ItemEntity.class, this
                    .getBoundingBox().inflate((double) vec3i.getX(), (double) vec3i.getY(), (double) vec3i.getZ()))) {
                if (!itemEntity.isRemoved() && !itemEntity.getItem().isEmpty() && !itemEntity.hasPickUpDelay()) {
                    ItemStack itemStack = itemEntity.getItem();
                    int i = itemStack.getCount();
                    if (this.getLivingInventory().insertStack(itemStack)) {
                        this.take(itemEntity, i);
                        if (itemStack.isEmpty()) {
                            itemEntity.discard();
                            itemStack.setCount(i);
                        }
                    }
                }
            }
        }
    }

    // Attacking function (LivingEntities don't attack by default)
    @Override
    public boolean doHurtTarget(Entity target) {
        noActionTime = 0;
        float f = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float g = (float) this.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
        if (target instanceof LivingEntity) {
            f += EnchantmentHelper.getDamageBonus(this.getMainHandItem(), ((LivingEntity) target).getMobType());
            g += (float) EnchantmentHelper.getKnockbackBonus(this);
        }

        int i = EnchantmentHelper.getFireAspect(this);
        if (i > 0) {
            target.setSecondsOnFire(i * 4);
        }

        boolean bl = target.hurt(this.damageSources().mobAttack(this), f);
        if (bl) {
            if (g > 0.0F && target instanceof LivingEntity) {
                ((LivingEntity) target).knockback((double) (g * 0.5F),
                        (double) Mth.sin(this.getYRot() * ((float) Math.PI / 180F)),
                        (double) (-Mth.cos(this.getYRot() * ((float) Math.PI / 180F))));
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.6, (double) 1.0F, 0.6));
            }

            this.doEnchantDamageEffects(this, target);
            this.setLastHurtMob(target);
        }

        return bl;
    }

    @Override
    public void knockback(double strength, double x, double z) {
        if (this.hurtMarked) {
            super.knockback(strength, x, z);
        }
    }

    // Inventory of abstract methods from LivingEntity
    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return getLivingInventory().armor;
    }

    public ItemStack getItemBySlot(EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND) {
            return this.inventory.getMainHandStack();
        } else if (slot == EquipmentSlot.OFFHAND) {
            return this.inventory.offHand.get(0);
        } else {
            return slot.getType() == EquipmentSlot.Type.ARMOR ? this.inventory.armor.get(slot.getIndex())
                    : ItemStack.EMPTY;
        }
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND) {
            this.inventory.setItem(this.inventory.selectedSlot, stack);
        } else if (slot == EquipmentSlot.OFFHAND) {
            this.inventory.offHand.set(0, stack);
        } else if (slot.getType() == EquipmentSlot.Type.ARMOR) {
            inventory.armor.set(slot.getIndex(), stack);
        }
    }

    // Useful getters
    public Character getCharacter() {
        return character;
    }

    public void setCharacter(Character character) {
        this.character = character;
    }

    // For rendering
    public Vec3 lerpVelocity(float delta) {
        return this.lastVelocity.lerp(this.getDeltaMovement(), (double) delta);
    }

    // Override the spawning packet
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return AutomatonSpawnPacket.create(this);
    }

    // Override the name to be taken from the character instance
    @Override
    public Component getDisplayName() {
        if (character == null) {
            return super.getDisplayName();
        }
        return Component.literal(character.shortName());
    }
}
