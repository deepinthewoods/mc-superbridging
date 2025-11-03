package ninja.trek.superbridging.client;

import com.mojang.logging.LogUtils;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;

/**
 * Handles the client-side bridging automation described in {@code plan.md}.
 * The controller watches for the use key, evaluates bridge start conditions,
 * and submits legitimate placement interactions while the key remains held.
 */
public final class BridgingController {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final BridgingController INSTANCE = new BridgingController();
	private static final Direction[] HORIZONTALS = new Direction[] {
		Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
	};
	private static final double START_RAYCAST_RANGE = 5.0D;
	private static final int MAX_FAILURE_TICKS = 8;
	private static final double DIRECTION_THRESHOLD = 0.3D;

	private enum State {
		IDLE,
		ARMED,
		BRIDGING
	}

	private enum HotbarAvailability {
		READY,
		HOLDING_NON_BLOCK,
		OUT_OF_BLOCKS
	}

	private final boolean debugEnabled;
	private State state = State.IDLE;
	private boolean useKeyPressedLastTick;
	private BlockPos anchorBlock;
	private Vec3d lastForward = Vec3d.ZERO;
	private ItemStack primedStack = ItemStack.EMPTY;
	private int consecutiveFailures;
	private boolean performingPlacement;

	private BridgingController() {
		this.debugEnabled = FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	public static BridgingController getInstance() {
		return INSTANCE;
	}

	public void tick(MinecraftClient client) {
		if (client == null) {
			return;
		}

		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;

		if (player == null || world == null) {
			reset();
			return;
		}

		boolean usePressed = client.options.useKey.isPressed();

		if (!usePressed) {
			if (state != State.IDLE) {
				debug("Use key released; exiting bridging mode");
			}
			reset();
			useKeyPressedLastTick = false;
			return;
		}

		if (!useKeyPressedLastTick && usePressed) {
			onUseKeyPressed(player);
		}

		if (state == State.ARMED) {
			tryStartBridging(player);
		} else if (state == State.BRIDGING) {
			performBridging(client, player);
		}

		useKeyPressedLastTick = usePressed;
	}

	private void onUseKeyPressed(ClientPlayerEntity player) {
		ItemStack held = player.getMainHandStack();
		if (!isPlaceableBlock(held)) {
			debug("Use key pressed without a block in hand; staying idle");
			reset();
			return;
		}

		state = State.ARMED;
		primedStack = held.copy();
		anchorBlock = null;
		lastForward = Vec3d.ZERO;
		consecutiveFailures = 0;
		debug("Armed bridging with {}", held);
	}

	private void tryStartBridging(ClientPlayerEntity player) {
		BlockPos candidate = findStartCandidate(player);
		if (candidate == null) {
			return;
		}

		anchorBlock = candidate;
		lastForward = getHorizontalForward(player);
		state = State.BRIDGING;
		consecutiveFailures = 0;
		debug("Bridging mode engaged; anchor {}", anchorBlock);
	}

	private void performBridging(MinecraftClient client, ClientPlayerEntity player) {
		ClientPlayerInteractionManager interactionManager = client.interactionManager;
		if (interactionManager == null) {
			return;
		}

		if (player.isSpectator() || player.getAbilities().flying) {
			debug("Player cannot bridge in this state; resetting");
			reset();
			return;
		}

		HotbarAvailability hotbarState = ensureHotbarHasBlocks(player);
		if (hotbarState == HotbarAvailability.OUT_OF_BLOCKS) {
			consecutiveFailures = 0;
			return;
		}
		if (hotbarState == HotbarAvailability.HOLDING_NON_BLOCK) {
			consecutiveFailures = 0;
			return;
		}

		Vec3d forward = getHorizontalForward(player);
		if (forward.lengthSquared() < 1.0E-4 && lastForward.lengthSquared() >= 1.0E-4) {
			forward = lastForward;
		} else if (forward.lengthSquared() >= 1.0E-4) {
			lastForward = forward;
		}

		if (forward.lengthSquared() < 1.0E-4) {
			return;
		}

		Set<BlockPos> targets = gatherTargets(player, forward);
		for (BlockPos pos : targets) {
			if (!((ClientWorld) player.getEntityWorld()).isAir(pos)) {
				continue;
			}

			ItemStack placingStack = player.getMainHandStack();
			BlockHitResult hit = createPlacementHit(player, pos, placingStack);
			if (hit == null) {
				debug("No support yet for {}; skipping this tick", pos);
				continue;
			}

			ActionResult actionResult;
			performingPlacement = true;
			try {
				actionResult = interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
			} finally {
				performingPlacement = false;
			}
			if (actionResult.isAccepted()) {
				player.swingHand(Hand.MAIN_HAND);
				consecutiveFailures = 0;
				anchorBlock = pos;
				continue;
			}

			consecutiveFailures++;
			if (consecutiveFailures >= MAX_FAILURE_TICKS) {
				debug("Too many placement failures ({}); resetting", consecutiveFailures);
				reset();
				return;
			}
		}
	}

	private Set<BlockPos> gatherTargets(ClientPlayerEntity player, Vec3d forward) {
		Set<BlockPos> positions = new LinkedHashSet<>();
		BlockPos underFoot = BlockPos.ofFloored(player.getX(), player.getY() - 1.0D, player.getZ());
		positions.add(underFoot);

		double absX = Math.abs(forward.x);
		double absZ = Math.abs(forward.z);
		int stepX = resolveStep(forward.x);
		int stepZ = resolveStep(forward.z);
		if (stepX == 0 && stepZ == 0) {
			Direction facing = player.getHorizontalFacing();
			stepX = facing.getOffsetX();
			stepZ = facing.getOffsetZ();
		 }

		if (absX >= absZ) {
			addTarget(positions, underFoot, stepX, 0, 0);
		} else {
			addTarget(positions, underFoot, 0, 0, stepZ);
		}

		if (stepX != 0 && stepZ != 0) {
			addTarget(positions, underFoot, stepX, 0, stepZ);
			addTarget(positions, underFoot, stepX * 2, 0, stepZ * 2);
		} else {
			addTarget(positions, underFoot, stepX * 2, 0, stepZ * 2);
		}

		return positions;
	}

	private int resolveStep(double component) {
		if (component > DIRECTION_THRESHOLD) {
			return 1;
		}
		if (component < -DIRECTION_THRESHOLD) {
			return -1;
		}
		return 0;
	}

	private void addTarget(Set<BlockPos> positions, BlockPos origin, int dx, int dy, int dz) {
		if (dx == 0 && dy == 0 && dz == 0) {
			return;
		}
		positions.add(origin.add(dx, dy, dz));
	}

	private BlockPos resolveTargetFromAim(ClientPlayerEntity player, int yLevel) {
		Vec3d eyePos = player.getCameraPosVec(1.0F);
		Vec3d look = player.getRotationVec(1.0F);
		double vertical = look.y;
		if (Math.abs(vertical) < 1.0E-5) {
			return null;
		}

		double planeY = yLevel + 1.0D - 1.0E-3D;
		double distance = (planeY - eyePos.y) / vertical;
		if (!Double.isFinite(distance) || distance < 0.0D || distance > START_RAYCAST_RANGE) {
			return null;
		}

		Vec3d intersection = eyePos.add(look.multiply(distance));
		return BlockPos.ofFloored(intersection.x, yLevel, intersection.z);
	}

	private BlockHitResult createPlacementHit(ClientPlayerEntity player, BlockPos placeAt, ItemStack stack) {
		ClientWorld world = (ClientWorld) player.getEntityWorld();
		BlockPos bestSupport = null;
		Direction bestFace = null;
		double bestScore = Double.NEGATIVE_INFINITY;

		for (Direction direction : HORIZONTALS) {
			BlockPos neighbor = placeAt.offset(direction);
			if (!world.isChunkLoaded(neighbor)) {
				continue;
			}
			if (!canAttachTo(world, neighbor, direction.getOpposite())) {
				continue;
			}
			double score = -player.squaredDistanceTo(Vec3d.ofCenter(neighbor));
			if (score > bestScore) {
				bestScore = score;
				bestSupport = neighbor;
				bestFace = direction.getOpposite();
			}
		}

		BlockPos below = placeAt.down();
		if (world.isChunkLoaded(below) && canAttachTo(world, below, Direction.UP)) {
			double score = -player.squaredDistanceTo(Vec3d.ofCenter(below));
			if (score > bestScore) {
				bestScore = score;
				bestSupport = below;
				bestFace = Direction.UP;
			}
		}

		if (bestSupport == null && anchorBlock != null && anchorBlock.getSquaredDistance(placeAt) <= 9) {
			if (!world.isAir(anchorBlock)) {
				Direction fallback = fallbackDirection(placeAt, anchorBlock);
				if (fallback != null && canAttachTo(world, anchorBlock, fallback)) {
					bestSupport = anchorBlock;
					bestFace = fallback;
				}
			}
		}

		if (bestSupport == null || bestFace == null) {
			return null;
		}

		Vec3d faceVector = new Vec3d(bestFace.getOffsetX(), bestFace.getOffsetY(), bestFace.getOffsetZ()).multiply(0.5D);
		Vec3d hitPos = Vec3d.ofCenter(bestSupport).add(faceVector);
		if (isSlab(stack) && bestFace.getAxis().isHorizontal()) {
			double baseY = placeAt.getY();
			double offset = shouldPlaceTopHalf(player) ? 0.875D : 0.125D;
			hitPos = new Vec3d(hitPos.x, baseY + offset, hitPos.z);
		}
		return new BlockHitResult(hitPos, bestFace, bestSupport, false);
	}

	private boolean canAttachTo(World world, BlockPos supportPos, Direction face) {
		if (world.isAir(supportPos)) {
			return false;
		}

		return world.getBlockState(supportPos).isSideSolidFullSquare(world, supportPos, face);
	}

	private Direction fallbackDirection(BlockPos placeAt, BlockPos support) {
		int dx = Integer.signum(placeAt.getX() - support.getX());
		int dz = Integer.signum(placeAt.getZ() - support.getZ());
		if (dx == 0 && dz == 0) {
			return null;
		}
		return Direction.fromVector(dx, 0, dz, Direction.DOWN);
	}

	private Vec3d getHorizontalForward(ClientPlayerEntity player) {
		float yaw = player.getYaw();
		double rad = Math.toRadians(yaw);
		double x = -MathHelper.sin((float) rad);
		double z = MathHelper.cos((float) rad);
		Vec3d horizontal = new Vec3d(x, 0.0D, z);
		double lengthSq = horizontal.lengthSquared();
		if (lengthSq < 1.0E-6) {
			return Vec3d.ZERO;
		}
		return horizontal.normalize();
	}

	private HotbarAvailability ensureHotbarHasBlocks(ClientPlayerEntity player) {
		ItemStack mainHand = player.getMainHandStack();
		if (isPlaceableBlock(mainHand)) {
			if (primedStack.isEmpty() || !mainHand.isOf(primedStack.getItem())) {
				primedStack = mainHand.copy();
			}
			return HotbarAvailability.READY;
		}

		if (mainHand.isEmpty()) {
			if (primedStack.isEmpty()) {
				return HotbarAvailability.OUT_OF_BLOCKS;
			}
			int matchingSlot = findMatchingHotbarSlot(player);
			if (matchingSlot >= 0) {
				player.getInventory().setSelectedSlot(matchingSlot);
				ItemStack swapped = player.getMainHandStack();
				if (isPlaceableBlock(swapped) && matchesPrimed(swapped)) {
					return HotbarAvailability.READY;
				}
			}
			primedStack = ItemStack.EMPTY;
			return HotbarAvailability.OUT_OF_BLOCKS;
		}

		boolean hasMatch = findMatchingHotbarSlot(player) >= 0;
		if (!hasMatch) {
			primedStack = ItemStack.EMPTY;
		}
		return hasAnyPlaceableHotbarBlock(player) ? HotbarAvailability.HOLDING_NON_BLOCK : HotbarAvailability.OUT_OF_BLOCKS;
	}

	private int findMatchingHotbarSlot(ClientPlayerEntity player) {
		if (primedStack.isEmpty()) {
			return -1;
		}
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (matchesPrimed(stack)) {
				return slot;
			}
		}
		return -1;
	}

	private boolean hasAnyPlaceableHotbarBlock(ClientPlayerEntity player) {
		for (int slot = 0; slot < 9; slot++) {
			if (isPlaceableBlock(player.getInventory().getStack(slot))) {
				return true;
			}
		}
		return false;
	}

	private boolean matchesPrimed(ItemStack stack) {
		return !primedStack.isEmpty() && !stack.isEmpty() && stack.isOf(primedStack.getItem());
	}

	private boolean isPlaceableBlock(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
	}

	private boolean isSlab(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		return blockItem.getBlock() instanceof SlabBlock;
	}

	private boolean shouldPlaceTopHalf(ClientPlayerEntity player) {
		ClientWorld world = (ClientWorld) player.getEntityWorld();
		BlockPos footPos = BlockPos.ofFloored(player.getX(), player.getY() - 0.0001D, player.getZ());
		BlockState state = world.getBlockState(footPos);
		if (state.getBlock() instanceof SlabBlock && state.contains(SlabBlock.TYPE)) {
			SlabType type = state.get(SlabBlock.TYPE);
			if (type == SlabType.TOP) {
				return true;
			}
			if (type == SlabType.BOTTOM) {
				return false;
			}
		}
		return true;
	}

	public boolean shouldSuppressManualPlacement() {
		return state == State.BRIDGING && !performingPlacement;
	}

	public boolean isBridging() {
		return state == State.BRIDGING;
	}

	public boolean hasStartCandidate(MinecraftClient client) {
		if (client == null) {
			return false;
		}

		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			return false;
		}

		if (!isPlaceableBlock(player.getMainHandStack())) {
			return false;
		}

		return findStartCandidate(player) != null;
	}

	private void reset() {
		state = State.IDLE;
		anchorBlock = null;
		lastForward = Vec3d.ZERO;
		primedStack = ItemStack.EMPTY;
		consecutiveFailures = 0;
		performingPlacement = false;
	}

	private void debug(String message, Object... args) {
		if (debugEnabled) {
			LOGGER.debug("[SuperBridging] " + message, args);
		}
	}

	private BlockPos findStartCandidate(ClientPlayerEntity player) {
		if (!player.isOnGround() || player.isSpectator() || player.getAbilities().flying) {
			return null;
		}

		HitResult result = player.raycast(START_RAYCAST_RANGE, 1.0F, false);
		if (result.getType() != HitResult.Type.MISS) {
			return null;
		}

		int yLevel = player.getBlockPos().getY() - 1;
		BlockPos target = resolveTargetFromAim(player, yLevel);
		if (target == null) {
			return null;
		}

		ClientWorld world = (ClientWorld) player.getEntityWorld();
		if (!world.isAir(target)) {
			return null;
		}

		BlockHitResult supportHit = createPlacementHit(player, target, player.getMainHandStack());
		if (supportHit == null) {
			return null;
		}

		return target;
	}
}
