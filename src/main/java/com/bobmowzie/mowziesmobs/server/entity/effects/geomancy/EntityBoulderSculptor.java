package com.bobmowzie.mowziesmobs.server.entity.effects.geomancy;

import com.bobmowzie.mowziesmobs.client.model.tools.MathUtils;
import com.bobmowzie.mowziesmobs.server.entity.EntityHandler;
import com.bobmowzie.mowziesmobs.server.entity.sculptor.EntitySculptor;
import com.google.common.collect.Iterables;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.royawesome.jlibnoise.MathHelper;

import java.util.List;

public class EntityBoulderSculptor extends EntityBoulderProjectile {
    private static final float MAX_DIST_HORIZONTAL = 4.0f;
    private static final float MAX_DIST_VERTICAL = 2.4f;
    private static final int MAX_TRIES = 10;

    private EntityBoulderSculptor nextBoulder;
    private EntitySculptor sculptor;
    private EntityPillar pillar;

    protected boolean isMainPath = false;

    protected boolean descending = false;

    private boolean replacementBoulder = false;
    private boolean spawnedNextBoulders = false;

    private int timeUntilActivation = -1;

    private float orbitSpeed = 0.0f;

    public EntityBoulderSculptor(EntityType<? extends EntityBoulderSculptor> type, Level world) {
        super(type, world);
    }

    public EntityBoulderSculptor(EntityType<? extends EntityBoulderSculptor> type, Level world, LivingEntity caster, BlockState blockState, BlockPos pos, GeomancyTier tier) {
        super(type, world, caster, blockState, pos, tier);
    }

    public EntityBoulderSculptor(EntityType<? extends EntityBoulderSculptor> type, EntityBoulderSculptor other) {
        super(type, other.level(), other.getCaster(), other.storedBlock, other.blockPosition(), other.getTier());
    }

    public void descend() {
        this.descending = true;
    }

    @Override
    public boolean doRemoveTimer() {
        return false;
    }

    // When a boulder is fired, it replaces itself with a delayed boulder
    public void delayActivation(int delay) {
        timeUntilActivation = delay;
    }

    @Override
    public void tick() {
        super.tick();
        if (sculptor == null || pillar == null || sculptor.getHealth() <= 0.0) {
            if (getCaster() instanceof EntitySculptor) {
                sculptor = (EntitySculptor) getCaster();
                if (!level().isClientSide()) sculptor.boulders.add(this);
                pillar = sculptor.getPillar();

                if (!sculptor.isFighting() && !sculptor.isTesting()) explode();
            }
        }

        // Orbit around sculptor pillar
        if (!isTravelling() && sculptor != null && sculptor.getTarget() != null) {
            if (orbitSpeed < 0.02) orbitSpeed += 0.001;
            Vec3 between = this.position().subtract(sculptor.position());
            between = between.yRot(orbitSpeed);
            setPos(between.add(sculptor.position()));
        }
        else {
            if (orbitSpeed > 0) orbitSpeed -= 0.001;
        }

        if (!level().isClientSide() && tickCount > 2 && (sculptor == null || sculptor.isRemoved() || pillar == null || pillar.isRemoved() || (pillar.isFalling() && !descending))) {
            explode();
            return;
        }

        if (!replacementBoulder && tickCount >= 2 && !spawnedNextBoulders) {
            nextBoulders();
        }

        if (!replacementBoulder && pillar != null && !level().isClientSide()) {
            if (pillar.getY() + pillar.getHeight() >= this.getY() && !active) activate();
            else if (!pillar.isRising() && !active && this.getY() - pillar.getY() < pillar.tickCount) activate();
        }

        if (descending) {
            move(MoverType.SELF, new Vec3(0, -EntityPillar.RISING_SPEED, 0));
            if (Iterables.size(level().getBlockCollisions(this, getBoundingBox().inflate(0.1))) > 0) {
                discard();
                return;
            }
        }

        if (!level().isClientSide() && replacementBoulder) {
            if (timeUntilActivation > 0) {
                timeUntilActivation--;
            } else if (timeUntilActivation == 0) {
                activate();
            }
        }
    }

    public void nextBoulders() {
        if (getCaster() == null || sculptor == null || pillar == null) return;
        spawnedNextBoulders = true;
        if (level().isClientSide()) return;

        // If it's not the main path, path has a random chance of ending. Chance is weighted by the number of live paths.
        if (!isMainPath) {
            if (random.nextFloat() < MathUtils.fit(sculptor.numLivePaths, 3, 7, 0.0, 0.4)) {
                sculptor.numLivePaths--;
                return;
            }
        }

        // Path has a random chance of branching. Chance is weighted by the number of live paths.
        int numNextBoulders = 1;
        if (random.nextFloat() < MathUtils.fit(sculptor.numLivePaths, 1, 5, 0.2, 0.0)) {
            numNextBoulders = 2;
        }

        for (int i = 0; i < numNextBoulders; i++) {
            boolean success = nextSingleBoulder();
            if (success && i > 0) sculptor.numLivePaths++;
        }

//        this.activate();
    }

    @Override
    protected boolean startActive() {
        return false;
    }

    public boolean nextSingleBoulder() {
        int whichTierIndex = (int) (Math.pow(random.nextFloat(), 2) * (GeomancyTier.values().length - 2) + 1);
        GeomancyTier nextTier = GeomancyTier.values()[whichTierIndex];
        EntityBoulderSculptor nextBoulder = new EntityBoulderSculptor(EntityHandler.BOULDER_SCULPTOR.get(), level(), getCaster(), getBlock(), blockPosition(), nextTier);

        // Try many times to find a good placement for the next boulder
        for (int j = 0; j < MAX_TRIES; j++) {
            Vec3 randomPos;
            if (getHeightFrac() < 1f) {
                randomPos = chooseRandomLocation(nextBoulder);
            }
            // If the platform is already at max height, next platform should move towards sculptor
            else if (position().multiply(1, 0, 1).distanceTo(sculptor.position().multiply(1, 0, 1)) > MAX_DIST_HORIZONTAL) {
                randomPos = chooseTowardsSculptorLocation(nextBoulder);
            } else return false;
            nextBoulder.setPos(randomPos);

            // Make sure boulder has no collision, even with the future fully-grown pillar
            if (
                    level().getEntitiesOfClass(EntityBoulderSculptor.class, nextBoulder.getBoundingBox(), (b) -> b != this).isEmpty()
                    && Iterables.size(level().getBlockCollisions(nextBoulder, nextBoulder.getBoundingBox())) == 0
                    && !pillar.getBoundingBox().setMaxY(pillar.getY() + EntitySculptor.TEST_HEIGHT).intersects(nextBoulder.getBoundingBox())
            ) {
                // Check nearby boulders below to make sure this boulder doesn't block jumping path
                AABB toCheck = nextBoulder.getBoundingBox().inflate(MAX_DIST_HORIZONTAL, MAX_DIST_VERTICAL / 2f + 1.5f, MAX_DIST_HORIZONTAL).move(0, -MAX_DIST_VERTICAL / 2f - 1.5f, 0);
                List<EntityBoulderSculptor> platforms = level().getEntitiesOfClass(EntityBoulderSculptor.class, toCheck);
                boolean obstructsPath = false;
                for (EntityBoulderSculptor platform : platforms) {
                    if (platform != nextBoulder && !nextBoulder.checkJumpPath(platform)) {
                        obstructsPath = true;
                        break;
                    }
                }
                if (!obstructsPath) {
                    level().addFreshEntity(nextBoulder);
                    if (isMainPath && this.nextBoulder == null) {
                        this.nextBoulder = nextBoulder;
                        this.nextBoulder.setMainPath();
                    }
                    return true;
                }
            }
        }
        // If the main path can't find a good placement, it needs to try again next tick. Branched paths can end.
        if (isMainPath) spawnedNextBoulders = false;
        return false;
    }

    protected Vec3 chooseRandomLocation(EntityBoulderSculptor nextBoulder) {
        EntityDimensions thisDims = SIZE_MAP.get(this.getTier());
        EntityDimensions nextDims = SIZE_MAP.get(nextBoulder.getTier());
        Vec3 startLocation = position();
        Vec2 fromPillarPos = new Vec2((float) (getCaster().getX() - startLocation.x), (float) (getCaster().getZ() - startLocation.z));
        float horizontalOffset = Mth.nextFloat(this.random, 1, MAX_DIST_HORIZONTAL) + thisDims.width/2f + nextDims.width/2f;
        float verticalOffset = Mth.nextFloat(this.random, 0, MAX_DIST_VERTICAL) - (nextDims.height - thisDims.height);

        float baseAngle = (float) -Math.toDegrees(Math.atan2(fromPillarPos.y, fromPillarPos.x));
        // Minimum and maximum angles force the angle to approach 90 degrees as it gets too close or too far from the pillar
        float minRandomAngle = (float) (Math.min(Math.pow(3f, -fromPillarPos.length() + 3), 1f) * 90f);
        double radius = EntitySculptor.testRadiusAtHeight(startLocation.y + verticalOffset + nextDims.height - pillar.getY());
        float maxRandomAngle = 180f - (float) (Math.min(Math.pow(3f, fromPillarPos.length() - radius), 1f) * 90f);
        float randomAngle = Mth.nextFloat(this.random, minRandomAngle, maxRandomAngle);
        if (random.nextBoolean()) randomAngle *= -1;
        // random angle tends towards center as the platforms reach higher up
        randomAngle *= 1f - Math.pow(getHeightFrac(), 5f) * 0.75;
        Vec3 offset = new Vec3(horizontalOffset, verticalOffset, 0);
        float finalAngle = (float) Math.toRadians(MathHelper.wrapAngle(baseAngle + randomAngle));
        offset = offset.yRot(finalAngle);
        Vec3 nextLocation = startLocation.add(offset);
        if (nextLocation.y() + nextDims.height > pillar.getY() + EntitySculptor.TEST_HEIGHT) {
            nextLocation = new Vec3(nextLocation.x(), pillar.getY() + EntitySculptor.TEST_HEIGHT - nextDims.height, nextLocation.z());
        }

        return nextLocation;
    }

    // For when the platforms have reached max height and just need to head towards sculptor
    protected Vec3 chooseTowardsSculptorLocation(EntityBoulderSculptor nextBoulder) {
        EntityDimensions thisDims = SIZE_MAP.get(this.getTier());
        EntityDimensions nextDims = SIZE_MAP.get(nextBoulder.getTier());
        Vec3 startLocation = position();
        Vec2 fromPillarPos = new Vec2((float) (getCaster().getX() - startLocation.x), (float) (getCaster().getZ() - startLocation.z));
        float horizontalOffset = Mth.nextFloat(this.random, 1, MAX_DIST_HORIZONTAL) + thisDims.width/2f + nextDims.width/2f;

        float baseAngle = (float) -Math.toDegrees(Math.atan2(fromPillarPos.y, fromPillarPos.x));
        Vec3 offset = new Vec3(horizontalOffset, 0, 0);
        float finalAngle = (float) Math.toRadians(MathHelper.wrapAngle(baseAngle));
        offset = offset.yRot(finalAngle);

        return startLocation.add(offset);
    }

    public EntityBoulderSculptor getNextBoulder() {
        return nextBoulder;
    }

    public boolean checkJumpPath(EntityBoulderSculptor platform) {
        EntityBoulderSculptor next = platform.getNextBoulder();
        if (next == null) return true;
        EntityDimensions platDims = SIZE_MAP.get(platform.getTier());
        EntityDimensions nextDims = SIZE_MAP.get(next.getTier());

        Vec3 toNext = next.position().subtract(platform.position());
        Vec3 startPos = platform.position().add(0, platDims.height, 0).add(toNext.multiply(1, 0, 1).normalize().scale(platDims.width/2f));
        Vec3 endPos = next.position().add(0, nextDims.height, 0).add(toNext.multiply(1, 0, 1).normalize().scale(-nextDims.width/2f));

        double gravity = -net.minecraftforge.common.ForgeMod.ENTITY_GRAVITY.get().getDefaultValue();
        double jumpVelY = 1D; // Player y jump speed with jump boost II
        double heightDiff = endPos.y() - startPos.y();
        // Quadratic formula to solve for time it takes to complete jump
        double totalTime = (-jumpVelY - Math.sqrt(jumpVelY * jumpVelY - 4 * gravity * -heightDiff)) / (2 * gravity);
        // Use time to get needed x and z velocities
        double jumpVelX = (endPos.x() - startPos.x()) / totalTime;
        double jumpVelZ = (endPos.z() - startPos.z()) / totalTime;
        Vec3 jumpVel = new Vec3(jumpVelX, jumpVelY, jumpVelZ);

        AABB thisBounds = SIZE_MAP.get(this.getTier()).makeBoundingBox(this.position());
        int substeps = 5;
        for (int i = 0; i < substeps; i++) {
            double time = (totalTime/(double)substeps) * i;
            Vec3 jumpPosition = new Vec3(0, gravity * time * time, 0).add(jumpVel.scale(time)).add(startPos);
            AABB playerBounds = EntityType.PLAYER.getDimensions().makeBoundingBox(jumpPosition);
            if (thisBounds.intersects(playerBounds)) return false;
        }

        return true;
    }

    public void setMainPath() {
        isMainPath = true;
    }

    public float getHeightFrac() {
        if (getCaster() instanceof EntitySculptor) {
            EntitySculptor sculptor = (EntitySculptor) getCaster();
            EntityPillar pillar = sculptor.getPillar();
            if (pillar != null) {
                return (float) (position().y() + getBbHeight() - pillar.getY()) / EntitySculptor.TEST_HEIGHT;
            }
        }
        return -1;
    }

    @Override
    public void remove(RemovalReason p_146834_) {
        super.remove(p_146834_);
        if (sculptor != null) {
            sculptor.boulders.remove(this);
        }
    }

    @Override
    protected float fallingBlockCountMultiplier() {
        return 0.2f;
    }

    @Override
    protected boolean travellingBlockedBy(Entity entity) {
        return super.travellingBlockedBy(entity) && !(entity instanceof EntityBoulderSculptor);
    }

    @Override
    public boolean canCollideWith(Entity entity) {
        return super.canCollideWith(entity) && !(entity instanceof EntityBoulderSculptor);
    }

    @Override
    protected float getShootRingParticleScale() {
        return super.getShootRingParticleScale() * 4;
    }

    @Override
    public void shoot(Vec3 shootDirection) {
        super.shoot(shootDirection);
        EntityBoulderSculptor boulderSculptor = new EntityBoulderSculptor(EntityHandler.BOULDER_SCULPTOR.get(), this);
        boulderSculptor.setPos(this.position());
        boulderSculptor.replacementBoulder = true;
        boulderSculptor.delayActivation(40);
        level().addFreshEntity(boulderSculptor);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putBoolean("SpawnedNext", spawnedNextBoulders);
        compound.putBoolean("Descending", descending);
        compound.putBoolean("MainPath", isMainPath);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        spawnedNextBoulders = compound.getBoolean("SpawnedNext");
        descending = compound.getBoolean("Descending");
        isMainPath = compound.getBoolean("MainPath");
    }
}
