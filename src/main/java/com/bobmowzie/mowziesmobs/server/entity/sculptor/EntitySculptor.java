package com.bobmowzie.mowziesmobs.server.entity.sculptor;

import com.bobmowzie.mowziesmobs.MowziesMobs;
import com.bobmowzie.mowziesmobs.client.particle.ParticleHandler;
import com.bobmowzie.mowziesmobs.client.particle.util.AdvancedParticleBase;
import com.bobmowzie.mowziesmobs.client.particle.util.ParticleComponent;
import com.bobmowzie.mowziesmobs.client.particle.util.ParticleRotation;
import com.bobmowzie.mowziesmobs.server.ability.Ability;
import com.bobmowzie.mowziesmobs.server.ability.AbilityHandler;
import com.bobmowzie.mowziesmobs.server.ability.AbilitySection;
import com.bobmowzie.mowziesmobs.server.ability.AbilityType;
import com.bobmowzie.mowziesmobs.server.ability.abilities.mob.HurtAbility;
import com.bobmowzie.mowziesmobs.server.ai.UseAbilityAI;
import com.bobmowzie.mowziesmobs.server.config.ConfigHandler;
import com.bobmowzie.mowziesmobs.server.entity.EntityHandler;
import com.bobmowzie.mowziesmobs.server.entity.MowzieEntity;
import com.bobmowzie.mowziesmobs.server.entity.MowzieGeckoEntity;
import com.bobmowzie.mowziesmobs.server.entity.effects.geomancy.EntityBoulderProjectile;
import com.bobmowzie.mowziesmobs.server.entity.effects.geomancy.EntityBoulderSculptor;
import com.bobmowzie.mowziesmobs.server.entity.effects.geomancy.EntityGeomancyBase;
import com.bobmowzie.mowziesmobs.server.entity.effects.geomancy.EntityPillar;
import com.bobmowzie.mowziesmobs.server.inventory.ContainerSculptorTrade;
import com.bobmowzie.mowziesmobs.server.item.ItemHandler;
import com.bobmowzie.mowziesmobs.server.message.MessageLinkEntities;
import com.bobmowzie.mowziesmobs.server.potion.EffectGeomancy;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.scores.Team;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animation.*;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

public class EntitySculptor extends MowzieGeckoEntity {
    public static int TEST_HEIGHT = 50;
    public static int TEST_RADIUS_BOTTOM = 6;
    public static int TEST_RADIUS = 16;
    public static int TEST_MAX_RADIUS_HEIGHT = 10;
    public static double TEST_RADIUS_FALLOFF = 5;

    public static float DEFENSE_HEALTH_THRESHOLD = 0.85f;

    public static final AbilityType<EntitySculptor, HurtAbility<EntitySculptor>> HURT_ABILITY = new AbilityType<>("sculptor_hurt", (type, entity) -> new HurtAbility<>(type, entity,RawAnimation.begin().thenPlay("hurt"), 16, 0));
    public static final AbilityType<EntitySculptor, SculptorDieAbility> DIE_ABILITY = new AbilityType<>("sculptor_die", SculptorDieAbility::new);
    public static final AbilityType<EntitySculptor, StartTestAbility> START_TEST = new AbilityType<>("testStart", StartTestAbility::new);
    public static final AbilityType<EntitySculptor, FailTestAbility> FAIL_TEST = new AbilityType<>("testFail", FailTestAbility::new);
    public static final AbilityType<EntitySculptor, PassTestAbility> PASS_TEST = new AbilityType<>("testPass", PassTestAbility::new);
    public static final AbilityType<EntitySculptor, AttackAbility> ATTACK_ABILITY = new AbilityType<>("attack", AttackAbility::new);
    public static final AbilityType<EntitySculptor, GuardAbility> GUARD_ABILITY = new AbilityType<>("guard", GuardAbility::new);

    private static final EntityDataAccessor<ItemStack> DESIRES = SynchedEntityData.defineId(EntitySculptor.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> IS_TRADING = SynchedEntityData.defineId(EntitySculptor.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> IS_FIGHTING = SynchedEntityData.defineId(EntitySculptor.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<UUID>> TESTING_PLAYER = SynchedEntityData.defineId(EntitySculptor.class, EntityDataSerializers.OPTIONAL_UUID);

    public boolean handLOpen = true;
    public boolean handROpen = true;
    private Player customer;
    private Player testingPlayer;
    private Optional<Double> prevPlayerVelY;
    private Optional<Vec3> prevPlayerPosition;
    private int ticksAcceleratingUpward;
    private boolean testing;
    private boolean isTestObstructed;
    private boolean isTestObstructedSoFar;
    private int obstructionTestHeight;

    private EntityPillar.EntityPillarSculptor pillar;
    public int numLivePaths = 0;
    private HurtByTargetGoal hurtByTargetAI;

    protected Projectile guardProjectileTarget;

    public List<EntityBoulderSculptor> boulders = new ArrayList<>();

    public EntitySculptor(EntityType<? extends MowzieEntity> type, Level world) {
        super(type, world);
        xpReward = 30;
    }

    private static RawAnimation HURT = RawAnimation.begin().thenPlay("hurt");

    @Override
    public AbilityType getHurtAbility() {
        return HURT_ABILITY;
    }

    @Override
    public AbilityType getDeathAbility() {
        return DIE_ABILITY;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(2, new UseAbilityAI<>(this, START_TEST, false));
        this.goalSelector.addGoal(1, new UseAbilityAI<>(this, DIE_ABILITY));
        this.goalSelector.addGoal(2, new UseAbilityAI<>(this, HURT_ABILITY, false));
        this.goalSelector.addGoal(4, new RunTestGoal(this));
        this.goalSelector.addGoal(3, new CombatBehaviorGoal(this));
        hurtByTargetAI = new HurtByTargetGoal(this) {

            @Override
            public void start() {
                super.start();
                if (mob instanceof EntitySculptor sculptor) {
                    sculptor.setTestingPlayer(null);
                    sculptor.setCustomer(null);
                }
            }

            @Override
            public boolean canUse() {
                return super.canUse() && getHealthRatio() < DEFENSE_HEALTH_THRESHOLD;
            }

            @Override
            public boolean canContinueToUse() {
                LivingEntity livingentity = this.mob.getTarget();
                if (livingentity == null) {
                    livingentity = this.targetMob;
                }

                if (livingentity == null) {
                    return false;
                } else if (!this.mob.canAttack(livingentity)) {
                    return false;
                } else {
                    Team team = this.mob.getTeam();
                    Team team1 = livingentity.getTeam();
                    if (team != null && team1 == team) {
                        return false;
                    } else {
                        double d0 = this.getFollowDistance();
                        double yDistMax = 70;
                        double yBase = mob.getY();
                        EntitySculptor sculptor = (EntitySculptor) this.mob;
                        if (sculptor.getPillar() != null) {
                            yBase = sculptor.getPillar().getY();
                        }
                        if (
                                this.mob.position().multiply(1, 0, 1).distanceToSqr(livingentity.position().multiply(1, 0, 1)) > d0 * d0 ||
                                livingentity.getY() < yBase - yDistMax ||
                                livingentity.getY() > mob.getY() + yDistMax
                        ) {
                            return false;
                        } else {
                            this.mob.setTarget(livingentity);
                            return true;
                        }
                    }
                }
            }
        };
        this.targetSelector.addGoal(3, hurtByTargetAI);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        Item tradeItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ConfigHandler.COMMON.MOBS.UMVUTHI.whichItem.get()));
        getEntityData().define(DESIRES, new ItemStack(tradeItem, ConfigHandler.COMMON.MOBS.UMVUTHI.howMany.get()));
        getEntityData().define(IS_TRADING, false);
        getEntityData().define(IS_FIGHTING, false);
        getEntityData().define(TESTING_PLAYER, Optional.empty());
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MowzieEntity.createAttributes().add(Attributes.ATTACK_DAMAGE, 10)
                .add(Attributes.MAX_HEALTH, 130)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.FOLLOW_RANGE, 40);
    }

    private static RawAnimation TEST_OBSTRUCTED = RawAnimation.begin().thenLoop("test_obstructed");

    @Override
    protected <E extends GeoEntity> void loopingAnimations(AnimationState<E> event) {
        event.getController().transitionLength(10);
        if (isTestObstructed && !isFighting()) {
            controller.setAnimation(TEST_OBSTRUCTED);
        }
        else {
            super.loopingAnimations(event);
        }
//        if (event.getController() instanceof MowzieAnimationController mowzieAnimationController) {
//            mowzieAnimationController.checkAndReloadAnims();
//        }
//        event.getController().setAnimation(RawAnimation.begin().thenLoop("attack_1"));
    }

    @Override
    public boolean causeFallDamage(float p_147187_, float p_147188_, DamageSource p_147189_) {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public void push(double x, double y, double z) {
        super.push(0, y, 0);
    }

    @Override
    public boolean canBePushedByEntity(Entity entity) {
        return false;
    }

    public void setDesires(ItemStack stack) {
        getEntityData().set(DESIRES, stack);
    }

    public ItemStack getDesires() {
        return getEntityData().get(DESIRES);
    }

    public boolean fulfillDesire(Slot input) {
        ItemStack desires = getDesires();
        if (canPayFor(input.getItem(), desires)) {
            input.remove(desires.getCount());
            return true;
        }
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void tick() {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        super.tick();
        if (testingPlayer == null && getTestingPlayerID().isPresent()) {
            testingPlayer = level().getPlayerByUUID(getTestingPlayerID().get());
        }

        if (testingPlayer != null) {
            getLookControl().setLookAt(testingPlayer);
        }
        else if (customer != null) {
            getLookControl().setLookAt(customer);
        }

        if (!testing && !isFighting()) {
            checkTestObstructedAtHeight(obstructionTestHeight);

            int height = EntitySculptor.TEST_HEIGHT + 3;
            obstructionTestHeight = (obstructionTestHeight + 1) % height;
            if (obstructionTestHeight == 0) {
                isTestObstructed = isTestObstructedSoFar;
                isTestObstructedSoFar = false;
            }
        }

//        if (getActiveAbility() == null && tickCount % 60 == 0) {
//            sendAbilityMessage(GUARD_ABILITY);
//        }
    }

    public boolean checkTestObstructed() {
        int height = EntitySculptor.TEST_HEIGHT + 3;
        for (int i = 0; i < height; i++) {
            checkTestObstructedAtHeight(i);
            if (isTestObstructed) return true;
        }
        return false;
    }

    private void checkTestObstructedAtHeight(int height) {
        BlockPos pos = blockPosition();
        int radius = EntitySculptor.TEST_RADIUS;
        for (int i = -radius; i < radius; i++) {
            for (int j = -radius; j < radius; j++) {
                Vec2 offset = new Vec2(i, j);
                BlockPos checkPos = pos.offset((int) offset.x, height, (int) offset.y);
                double testRadius = testRadiusAtHeight(height);
                if (offset.lengthSquared() < testRadius * testRadius) {
                    if (!level().getBlockState(checkPos).isAir()) {
                        isTestObstructed = true;
                        isTestObstructedSoFar = true;
                        if (level().isClientSide() && isPlayerInTestZone(Minecraft.getInstance().player) && blockHasExposedSide(checkPos)) {
                            MowziesMobs.PROXY.sculptorMarkBlock(this.getId(), checkPos);
                            ParticleRotation.FaceCamera faceCamera = new ParticleRotation.FaceCamera(0);
                            AdvancedParticleBase.spawnAlwaysVisibleParticle(level(), ParticleHandler.RING2.get(), 64, checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5, 0, 0, 0, faceCamera, 3.5F, 0.83f, 1, 0.39f, 1, 1, 20, true, false, new ParticleComponent[]{
                                    new ParticleComponent.PropertyControl(ParticleComponent.PropertyControl.EnumParticleProperty.ALPHA, ParticleComponent.KeyTrack.startAndEnd(0.7f, 0f), false),
                                    new ParticleComponent.PropertyControl(ParticleComponent.PropertyControl.EnumParticleProperty.SCALE, ParticleComponent.KeyTrack.startAndEnd(0f, 16.0f), false)
                            });
                        }
                    }
                }
            }
        }
    }

    private boolean blockHasExposedSide(BlockPos pos) {
        return !level().getBlockState(pos.north()).canOcclude() ||
                !level().getBlockState(pos.south()).canOcclude() ||
                !level().getBlockState(pos.east()).canOcclude() ||
                !level().getBlockState(pos.west()).canOcclude() ||
                !level().getBlockState(pos.above()).canOcclude() ||
                !level().getBlockState(pos.below()).canOcclude();
    }

    private void checkIfPlayerCheats() {
        if (testingPlayer == null) return;
        prevPlayerPosition = Optional.of(testingPlayer.position());
        if (!isTesting() || testingPlayer.isCreative()) return;

        // Check if player moved too far away
        if (testingPlayer != null && testingPlayer.position().multiply(1, 0, 1).distanceTo(position().multiply(1, 0, 1)) > TEST_RADIUS + 3) {
            playerCheated();
            return;
        }
        if (testingPlayer != null && pillar != null && testingPlayer.getY() < pillar.getY() - 10) {
            playerCheated();
            return;
        }

        // Check if testing player is flying
        if (testingPlayer != null && testingPlayer.getAbilities().flying) {
            playerCheated();
            return;
        }
        if (testingPlayer != null && !testingPlayer.onGround()) {
            double playerVelY = testingPlayer.getDeltaMovement().y();
            if (prevPlayerVelY != null && prevPlayerVelY.isPresent()) {
                double acceleration = playerVelY - prevPlayerVelY.get();
                if (acceleration >= 0.0) {
                    ticksAcceleratingUpward++;
                }
                else if (ticksAcceleratingUpward > 0) {
                    ticksAcceleratingUpward--;
                }
                if (ticksAcceleratingUpward > 5) {
                    playerCheated();
                    return;
                }
            }
            prevPlayerVelY = Optional.of(playerVelY);
        }
        else {
            ticksAcceleratingUpward = 0;
            prevPlayerVelY = Optional.empty();
        }

        // Check if testing player teleported
        if (testingPlayer != null) {
            Vec3 currPosition = testingPlayer.position();
            if (prevPlayerPosition != null && prevPlayerPosition.isPresent()) {
                if (currPosition.distanceTo(prevPlayerPosition.get()) > 3.0) {
                    playerCheated();
                    return;
                }
            }
        }
    }

    public void playerCheated() {
        if (isTesting() && testingPlayer != null) {
            sendAbilityMessage(FAIL_TEST);
        }
    }

    public static double testRadiusAtHeight(double height) {
        return TEST_RADIUS_BOTTOM + Math.pow(Math.min(height / (double) TEST_MAX_RADIUS_HEIGHT, 1), TEST_RADIUS_FALLOFF) * (TEST_RADIUS - TEST_RADIUS_BOTTOM);
    }

    public void setTrading(boolean trading) {
        entityData.set(IS_TRADING, trading);
    }

    public boolean isTrading() {
        return entityData.get(IS_TRADING);
    }

    public void setFighting(boolean fighting) {
        entityData.set(IS_FIGHTING, fighting);
    }

    public boolean isFighting() {
        return entityData.get(IS_FIGHTING);
    }

    public Player getCustomer() {
        return customer;
    }

    public void setCustomer(Player customer) {
        setTrading(customer != null);
        this.customer = customer;
    }

    public boolean isTesting() {
        return getTestingPlayerID().isPresent();
    }

    public Optional<UUID> getTestingPlayerID() {
        return getEntityData().get(TESTING_PLAYER);
    }

    public void setTestingPlayerID(UUID playerID) {
        if (playerID == null) getEntityData().set(TESTING_PLAYER, Optional.empty());
        else getEntityData().set(TESTING_PLAYER, Optional.of(playerID));
    }

    public void setTestingPlayer(Player testingPlayer) {
        this.testingPlayer = testingPlayer;
        setTestingPlayerID(testingPlayer == null ? null : testingPlayer.getUUID());
    }

    public void openGUI(Player playerEntity) {
        setCustomer(playerEntity);
        MowziesMobs.PROXY.setReferencedMob(this);
        if (!this.level().isClientSide && getTarget() == null && isAlive()) {
            playerEntity.openMenu(new MenuProvider() {
                @Override
                public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
                    return new ContainerSculptorTrade(id, EntitySculptor.this, playerInventory);
                }

                @Override
                public Component getDisplayName() {
                    return EntitySculptor.this.getDisplayName();
                }
            });
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (isTesting()) {
            if (player == testingPlayer) sendAbilityMessage(PASS_TEST);
        }
        else {
            if (canTradeWith(player) && getTarget() == null && isAlive()) {
                openGUI(player);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    public boolean canTradeWith(Player player) {
        return !isTrading() && !(getHealth() <= 0) && !testing;
    }

    public boolean doesItemSatisfyDesire(ItemStack stack) {
        return canPayFor(stack, getDesires());
    }

    private static boolean canPayFor(ItemStack stack, ItemStack worth) {
        return stack.getItem() == worth.getItem() && stack.getCount() >= worth.getCount();
    }

    public EntityPillar.EntityPillarSculptor getPillar() {
        return pillar;
    }

    public void setPillar(EntityPillar.EntityPillarSculptor pillar) {
        this.pillar = pillar;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
    }

    @Override
    public AbilityType<?, ?>[] getAbilities() {
        return new AbilityType[] {START_TEST, FAIL_TEST, PASS_TEST, HURT_ABILITY, DIE_ABILITY, ATTACK_ABILITY, GUARD_ABILITY};
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        if (testingPlayer != null && getTestingPlayerID().isPresent()) {
            compound.putUUID("TestingPlayer", getTestingPlayerID().get());
            compound.putInt("NumLivePaths", numLivePaths);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("TestingPlayer")) {
            testing = true;
            setTestingPlayerID(compound.getUUID("TestingPlayer"));
            numLivePaths = compound.getInt("NumLivePaths");
        }
    }

    public boolean isPlayerInTestZone(Player player) {
        double yDistMax = 12;
        double yBase = getY();
        if (getPillar() != null) {
            yBase = getPillar().getY();
        }
        return player.position().multiply(1, 0, 1).distanceToSqr(this.position().multiply(1, 0, 1)) < (TEST_RADIUS * TEST_RADIUS + 9) &&
                player.getY() > yBase - yDistMax &&
                player.getY() < yBase + TEST_HEIGHT + yDistMax;
    }

    @Override
    protected int getDeathDuration() {
        Ability deathAbility = getActiveAbility();
        if (deathAbility == null || !deathAbility.isUsing()) return 9;
        return 120;
    }

    public static class StartTestAbility extends Ability<EntitySculptor> {
        private static int MAX_RANGE_TO_GROUND = 12;

        private BlockPos spawnPillarPos;
        private BlockState spawnPillarBlock;

        public StartTestAbility(AbilityType<EntitySculptor, StartTestAbility> abilityType, EntitySculptor user) {
            super(abilityType, user, new AbilitySection[] {
                    new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.STARTUP, 18),
                    new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.ACTIVE, 34)
            });
        }

        @Override
        public boolean tryAbility() {
            Vec3 from = getUser().position();
            Vec3 to = from.subtract(0, MAX_RANGE_TO_GROUND, 0);
            BlockHitResult result = getUser().level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, getUser()));
            if (result.getType() != HitResult.Type.MISS) {
                this.spawnPillarPos = result.getBlockPos();
                this.spawnPillarBlock = getUser().level().getBlockState(spawnPillarPos);
                if (result.getDirection() != Direction.UP) {
                    BlockState blockAbove = getUser().level().getBlockState(spawnPillarPos.above());
                    if (blockAbove.isSuffocating(getUser().level(), spawnPillarPos.above()) || blockAbove.isAir())
                        return false;
                }
                return true;
            }
            return false;
        }

        private static final RawAnimation TEST_START_ANIM = RawAnimation.begin().then("testStart", Animation.LoopType.PLAY_ONCE);
        @Override
        public void start() {
            super.start();
            playAnimation(TEST_START_ANIM);
        }

        public static void placeStartingBoulders(EntitySculptor sculptor) {
            if (sculptor.pillar == null) return;
            RandomSource rand = sculptor.getRandom();
            int numStartBoulders = rand.nextInt(2, 5);
            float angleOffset = rand.nextFloat() * (float) (2f * Math.PI);
            for (int i = 0; i < numStartBoulders; i++) {
                float angleInc = (float) (2f * Math.PI) / ((float) numStartBoulders * 2f);
                float angle = angleOffset + angleInc * (i * 2) + rand.nextFloat() * angleInc;
                Vec3 spawnBoulderPos = sculptor.pillar.position().add(new Vec3(rand.nextFloat() * 3 + 3, 0, 0).yRot(angle));
                EntityBoulderSculptor boulderPlatform = new EntityBoulderSculptor(EntityHandler.BOULDER_SCULPTOR.get(), sculptor.level(), sculptor, Blocks.STONE.defaultBlockState(), BlockPos.ZERO, EntityGeomancyBase.GeomancyTier.MEDIUM);
                boulderPlatform.setPos(spawnBoulderPos.add(0, 1, 0));
                if (i == 0) boulderPlatform.setMainPath();
                sculptor.level().addFreshEntity(boulderPlatform);
            }
            sculptor.numLivePaths = numStartBoulders;
        }

        @Override
        protected void beginSection(AbilitySection section) {
            super.beginSection(section);
            if (!getUser().level().isClientSide() && section.sectionType == AbilitySection.AbilitySectionType.ACTIVE && spawnPillarPos != null) {

                if (spawnPillarBlock == null || !EffectGeomancy.isBlockUseable(spawnPillarBlock)) spawnPillarBlock = Blocks.STONE.defaultBlockState();
                getUser().pillar = new EntityPillar.EntityPillarSculptor(EntityHandler.PILLAR_SCULPTOR.get(), getUser().level(), getUser(), Blocks.STONE.defaultBlockState(), spawnPillarPos);
                getUser().pillar.setTier(EntityGeomancyBase.GeomancyTier.SMALL);
                getUser().pillar.setPos(spawnPillarPos.getX() + 0.5F, spawnPillarPos.getY() + 1, spawnPillarPos.getZ() + 0.5F);
                getUser().pillar.setDoRemoveTimer(false);
                if (getUser().pillar.checkCanSpawn()) {
                    getUser().level().addFreshEntity(getUser().pillar);
                }

                placeStartingBoulders(getUser());
            }
        }

        @Override
        public void tickUsing() {
            super.tickUsing();
            if (getCurrentSection().sectionType == AbilitySection.AbilitySectionType.ACTIVE) {
                List<LivingEntity> livingEntities = getUser().getEntityLivingBaseNearby(5, 5, 5, 5);
                for (LivingEntity livingEntity : livingEntities) {
                    Vec3 userPos = getUser().position().multiply(1, 0, 1);
                    Vec3 entityPos = livingEntity.position().multiply(1, 0, 1);
                    Vec3 vec = userPos.subtract(entityPos).normalize().scale(-Math.min(1.0 / userPos.distanceToSqr(entityPos), 2));
                    livingEntity.push(vec.x, vec.y, vec.z);
                }
            }
        }

        @Override
        public boolean canCancelActiveAbility() {
            return getUser().getActiveAbilityType() == HURT_ABILITY;
        }
    }

    public static abstract class EndTestAbility extends Ability<EntitySculptor> {

        public EndTestAbility(AbilityType<EntitySculptor, ? extends EndTestAbility> abilityType, EntitySculptor user, int recoveryDuration) {
            super(abilityType, user, new AbilitySection[] {
                    new AbilitySection.AbilitySectionInfinite(AbilitySection.AbilitySectionType.ACTIVE),
                    new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.RECOVERY, recoveryDuration)
            });
        }

        @Override
        public boolean tryAbility() {
            return getUser().pillar != null;
        }

        private static final RawAnimation TEST_FAIL_START_ANIM = RawAnimation.begin().then("test_fail_start", Animation.LoopType.PLAY_ONCE);
        @Override
        public void start() {
            super.start();
            playAnimation(TEST_FAIL_START_ANIM);
            if (getUser().pillar != null) getUser().pillar.startFalling();
            getUser().testing = false;
        }

        @Override
        public void tickUsing() {
            super.tickUsing();
            if (getCurrentSection().sectionType == AbilitySection.AbilitySectionType.ACTIVE) {
                if (!getUser().level().isClientSide() && getUser().pillar != null && getUser().pillar.getHeight() > 1) {
                    getUser().setPos(getUser().pillar.position().add(0, getUser().pillar.getHeight()+0.2, 0));
                }
                if (getUser().pillar == null || getUser().pillar.isRemoved()) {
                    AbilityHandler.INSTANCE.sendJumpToSectionMessage(getUser(), this.getAbilityType(), 1);
                }
            }
        }

        @Override
        protected boolean canContinueUsing() {
            return super.canContinueUsing() && getUser().getTarget() == null;
        }

        @Override
        public void end() {
            super.end();
            if (getUser() != null) {
                getUser().pillar = null;
                getUser().setTestingPlayer(null);
                getUser().testing = false;
                getUser().numLivePaths = 0;
            }
        }

        @Override
        public boolean canCancelActiveAbility() {
            return true;
        }

        @Override
        protected void beginSection(AbilitySection section) {
            super.beginSection(section);
            if (section.sectionType == AbilitySection.AbilitySectionType.RECOVERY) {
                playFinishingAnimation();
            }
        }

        protected abstract void playFinishingAnimation();
    }

    public static class FailTestAbility extends EndTestAbility {
        public FailTestAbility(AbilityType<EntitySculptor, ? extends EndTestAbility> abilityType, EntitySculptor user) {
            super(abilityType, user, 30);
        }

        private static RawAnimation TEST_FAIL_END = RawAnimation.begin().thenLoop("test_fail_end");

        @Override
        protected void playFinishingAnimation() {
            playAnimation(TEST_FAIL_END);
        }
    }

    public static class PassTestAbility extends EndTestAbility {

        public PassTestAbility(AbilityType<EntitySculptor, PassTestAbility> abilityType, EntitySculptor user) {
            super(abilityType, user, 120);
        }

        @Override
        public void start() {
            if (getUser().testingPlayer != null) {
                List<EntityBoulderSculptor> platforms = getUser().level().getEntitiesOfClass(EntityBoulderSculptor.class, getUser().testingPlayer.getBoundingBox().expandTowards(0, -6, 0));
                EntityBoulderSculptor platformBelowPlayer = platforms.get(0);
                platformBelowPlayer.descend();
            }
            super.start();
        }

        @Override
        public void end() {
            super.end();
            getUser().spawnAtLocation(ItemHandler.EARTHREND_GAUNTLET.get().getDefaultInstance());
        }

        @Override
        public boolean canCancelActiveAbility() {
            return false;
        }

        private static RawAnimation TEST_PASS_END = RawAnimation.begin().thenLoop("test_pass_end");

        @Override
        protected void playFinishingAnimation() {
            playAnimation(TEST_PASS_END);
        }
    }

    public static class AttackAbility extends Ability<EntitySculptor> {
        private EntityBoulderSculptor boulderToFire;
        private Vec3 prevTargetPos;
        private static final int STARTUP_TIME = 5;

        public AttackAbility(AbilityType abilityType, EntitySculptor user) {
            super(abilityType, user, new AbilitySection[] {
                    new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.STARTUP, STARTUP_TIME),
                    new AbilitySection.AbilitySectionInstant(AbilitySection.AbilitySectionType.ACTIVE),
                    new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.RECOVERY, 5)
            });
        }

        private static RawAnimation ATTACK_START = RawAnimation.begin().thenPlayAndHold("attack_1");

        @Override
        public void start() {
            super.start();
            playAnimation(ATTACK_START);
        }

        @Override
        public boolean canUse() {
            LivingEntity target = getUser().getTarget();
            if (target != null) {
                Collections.shuffle(getUser().boulders);
                for (EntityBoulderSculptor boulder : getUser().boulders) {
                    if (boulder.isRemoved()) continue;
                    if (!boulder.isFinishedRising()) continue;
                    if (!boulder.active) continue;
                    if (boulder.position().add(0, boulder.getBbHeight(), 0).distanceToSqr(target.position()) < 9) continue;
                    Vec3 vecBetweenSculptorAndTarget = getUser().getTarget().position().subtract(getUser().position()).normalize();
                    Vec3 vecBetweenSculptorAndBoulder = boulder.position().subtract(getUser().position()).normalize();
                    if (vecBetweenSculptorAndBoulder.dot(vecBetweenSculptorAndTarget) > 0.5) {
                        boulderToFire = boulder;
                        break;
                    }
                }
                if (boulderToFire == null) return false;
                prevTargetPos = target.position().add(0, target.getBbHeight() / 2.0, 0);
                return super.canUse();
            }
            return false;
        }

        @Override
        protected void beginSection(AbilitySection section) {
            super.beginSection(section);
            if (!getUser().level().isClientSide() && getUser().getTarget() != null) {
                LivingEntity target = getUser().getTarget();
                if (section.sectionType == AbilitySection.AbilitySectionType.ACTIVE) {
                    shootBoulderAtTarget(target, prevTargetPos, boulderToFire, 0.93f);
                    boulderToFire = null;
                }
            }
        }

        public static void shootBoulderAtTarget(LivingEntity target, Vec3 prevTargetPos, EntityBoulderProjectile boulderToFire, float timeScale) {
            Vec3 targetPos = target.position().add(0, target.getBbHeight() / 2.0, 0);
            double timeToReach = boulderToFire.position().subtract(targetPos).length() / boulderToFire.getSpeed();
            Vec3 targetMovement = targetPos.subtract(prevTargetPos).scale(timeToReach * timeScale * 1.0/4.0);
            targetMovement = targetMovement.multiply(1, 0, 1);
            Vec3 futureTargetPos = targetPos.add(targetMovement);
            Vec3 projectileMid = boulderToFire.position().add(0, boulderToFire.getBbHeight() / 2.0, 0);
            Vec3 shootVec = futureTargetPos.subtract(projectileMid).normalize();
            boulderToFire.shoot(shootVec.scale(boulderToFire.getSpeed()));
        }
    }

    public static class SculptorDieAbility extends Ability<EntitySculptor> {
        private static AbilitySection.AbilitySectionDuration END_SECTION = new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.RECOVERY, 76);

        public SculptorDieAbility(AbilityType abilityType, EntitySculptor user) {
            super(abilityType, user, new AbilitySection[] {
                    new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.STARTUP, 9),
                    new AbilitySection.AbilitySectionInfinite(AbilitySection.AbilitySectionType.ACTIVE),
                    END_SECTION
            });
        }

        private static final RawAnimation DEATH_START = RawAnimation.begin().thenPlayAndHold("death_start");
        private static final RawAnimation DEATH_END = RawAnimation.begin().thenPlayAndHold("death_end");

        @Override
        public void start() {
            super.start();
            playAnimation(DEATH_START);
        }

        @Override
        public void tickUsing() {
            super.tickUsing();
            if (getCurrentSection().sectionType == AbilitySection.AbilitySectionType.ACTIVE) {
                if (getUser().onGround()) nextSection();
            }
        }

        @Override
        protected void beginSection(AbilitySection section) {
            super.beginSection(section);
            if (getCurrentSection().sectionType == AbilitySection.AbilitySectionType.RECOVERY) {
                playAnimation(DEATH_END);
            }
        }
    }

    public static class GuardAbility extends Ability<EntitySculptor> {
        private EntityBoulderProjectile boulder;
        private UUID boulderID;
        private Entity target;
        private Vec3 prevTargetPos;

        public GuardAbility(AbilityType abilityType, EntitySculptor user) {
            super(abilityType, user, new AbilitySection[] {
                    new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.STARTUP, 11),
                    new AbilitySection.AbilitySectionInstant(AbilitySection.AbilitySectionType.ACTIVE),
                    new AbilitySection.AbilitySectionDuration(AbilitySection.AbilitySectionType.RECOVERY, 17)
            });
        }

        private static final RawAnimation BLOCK = RawAnimation.begin().thenPlay("guard");

        @Override
        public void start() {
            super.start();
            playAnimation(BLOCK);
            if (!getUser().level().isClientSide()) {
                target = getUser().guardProjectileTarget.getOwner();
                if (target != null) {
                    prevTargetPos = target.position().add(0, target.getBbHeight() / 2.0, 0);
                }
            }
        }

        @Override
        public void tickUsing() {
            super.tickUsing();
            if (getBoulder() != null && getBoulder().getCaster() != null) {
                getUser().getLookControl().setLookAt(getBoulder().getCaster(), 60f, 60f);
            }
        }

        @Override
        protected void beginSection(AbilitySection section) {
            super.beginSection(section);
            if (!getUser().level().isClientSide()) {
                if (getCurrentSection().sectionType == AbilitySection.AbilitySectionType.STARTUP) {
                    boulder = new EntityBoulderProjectile(EntityHandler.BOULDER_PROJECTILE.get(), getUser().level(), getUser(), Blocks.STONE.defaultBlockState(), BlockPos.ZERO, EntityGeomancyBase.GeomancyTier.SMALL);

                    Vec3 betweenSculptorAndProjectile = getUser().guardProjectileTarget.position().subtract(getUser().position()).normalize();
                    boulder.setPos(getUser().position().add(0, getUser().getBbHeight() / 2f, 0).add(betweenSculptorAndProjectile.scale(2.0f).subtract(0, boulder.getBbHeight()/2.0f, 0)));

                    boulder.activate();
                    getUser().level().addFreshEntity(boulder);
                    boulderID = boulder.getUUID();
                } else if (getCurrentSection().sectionType == AbilitySection.AbilitySectionType.ACTIVE && getBoulder() != null) {
                    if (getUser().getTarget() != null) {
                        AttackAbility.shootBoulderAtTarget(getUser().getTarget(), prevTargetPos, getBoulder(), 0.45f);
                    }
                    else {
                        getBoulder().explode();
                    }
                }
            }
        }

        @Override
        public CompoundTag writeNBT() {
            CompoundTag nbt = super.writeNBT();
            if (boulderID != null) {
                nbt.putUUID("boulder_guard", boulderID);
            }
            return nbt;
        }

        @Override
        public void readNBT(Tag nbt) {
            super.readNBT(nbt);
            CompoundTag compound = (CompoundTag) nbt;
            if (compound.hasUUID("boulder_guard")) {
                boulderID = compound.getUUID("boulder_guard");
            }
        }

        public EntityBoulderProjectile getBoulder() {
            if (boulder != null && !boulder.isRemoved()) {
                return boulder;
            }
            else if (boulderID != null && getUser().level() instanceof ServerLevel) {
                Entity entity = ((ServerLevel) getUser().level()).getEntity(boulderID);
                if (entity instanceof EntityBoulderProjectile) {
                    boulder = (EntityBoulderProjectile) entity;
                }
                return boulder;
            }
            return null;
        }

        @Override
        public boolean canCancelActiveAbility() {
            return getUser().getActiveAbilityType() == ATTACK_ABILITY || getUser().getActiveAbilityType() == HURT_ABILITY;
        }

        @Override
        public void end() {
            super.end();
            if (getBoulder() != null) {
                getBoulder().explode();
            }
        }
    }

    public static class RunTestGoal extends Goal {

        private final EntitySculptor sculptor;

        RunTestGoal(EntitySculptor sculptor) {
            this.sculptor = sculptor;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return sculptor.testingPlayer != null;
        }

        @Override
        public void start() {
            super.start();
            sculptor.testing = true;
            sculptor.sendAbilityMessage(START_TEST);
        }

        @Override
        public void tick() {
            super.tick();
            if (sculptor.testingPlayer == null) {
                sculptor.sendAbilityMessage(FAIL_TEST);
                sculptor.prevPlayerPosition = Optional.empty();
                sculptor.prevPlayerVelY = Optional.empty();
            }
            else if (sculptor.testing) {
                sculptor.checkIfPlayerCheats();
            }
        }

        @Override
        public void stop() {
            super.stop();
            sculptor.setTestingPlayer(null);
            sculptor.testing = false;
        }
    }

    public static class CombatBehaviorGoal extends Goal {
        private final EntitySculptor sculptor;
        protected final Predicate<Projectile> guardTargetSelector;

        private static final float GUARD_DISTANCE = 8;

        public CombatBehaviorGoal(EntitySculptor sculptor) {
            this.sculptor = sculptor;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Flag.LOOK));

            this.guardTargetSelector = target -> {
                Vec3 aActualMotion = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
                if (aActualMotion.length() < 0.1 || target.tickCount < 0) {
                    return false;
                }
                if (!sculptor.getSensing().hasLineOfSight(target)) return false;
                float dot = (float) target.getDeltaMovement().normalize().dot(sculptor.position().subtract(target.position()).normalize());
                return !(dot < 0.8);
            };
        }

        @Override
        public boolean canUse() {
            LivingEntity target = sculptor.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            super.start();
            sculptor.setFighting(true);
            if (sculptor.getPillar() != null && sculptor.getPillar().getHeight() < TEST_HEIGHT) {
                // TODO: When game quits while sculptor is fighting, pillar is removed but sculptor still thinks its there. So it will do this branch instead of making a new pillar
                sculptor.getPillar().startRising();
            }
            else {
                sculptor.sendAbilityMessage(START_TEST);
            }
        }

        @Override
        public void tick() {
            super.tick();
            if (sculptor.getActiveAbility() == null) {
                AbilityHandler.INSTANCE.sendAbilityMessage(sculptor, ATTACK_ABILITY);
            }
            if (sculptor.boulders.isEmpty() && sculptor.getActiveAbilityType() != START_TEST) {
                StartTestAbility.placeStartingBoulders(sculptor);
            }

            sculptor.guardProjectileTarget = this.getMostMovingTowardsMeEntity(Projectile.class, this.guardTargetSelector, this.sculptor, this.sculptor.getBoundingBox().inflate(GUARD_DISTANCE, GUARD_DISTANCE, GUARD_DISTANCE));
            if (sculptor.guardProjectileTarget != null) {
                AbilityHandler.INSTANCE.sendAbilityMessage(sculptor, GUARD_ABILITY);
            }

            if (sculptor.getTarget() != null && sculptor.getActiveAbilityType() != GUARD_ABILITY) {
                sculptor.getLookControl().setLookAt(sculptor.getTarget(), 30f, 30f);
            }
        }

        @Nullable
        private <T extends Projectile> T getMostMovingTowardsMeEntity(Class<? extends T> entityClazz, Predicate<? super T> predicate, LivingEntity entity, AABB p_225318_10_) {
            return this.getMostMovingTowardsMeEntityFromList(entity.level().getEntitiesOfClass(entityClazz, p_225318_10_, predicate), entity);
        }

        private <T extends Projectile> T getMostMovingTowardsMeEntityFromList(List<? extends T> entities, LivingEntity target) {
            double d0 = -2.0D;
            T t = null;

            for(T t1 : entities) {
                double d1 = t1.getDeltaMovement().normalize().dot(target.position().subtract(t1.position()).normalize());
                if (d1 > d0) {
                    d0 = d1;
                    t = t1;
                }
            }

            return t;
        }

        @Override
        public void stop() {
            super.stop();
            sculptor.setFighting(false);
            sculptor.sendAbilityMessage(FAIL_TEST);
        }
    }
}
