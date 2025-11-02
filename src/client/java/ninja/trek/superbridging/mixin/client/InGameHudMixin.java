package ninja.trek.superbridging.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;
import ninja.trek.superbridging.client.BridgingController;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
abstract class InGameHudMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	private static final Identifier BRIDGE_READY_TEXTURE = Identifier.of("superbridging", "hud/candidate_bridge_cursor");
	private static final Identifier BRIDGE_ACTIVE_TEXTURE = Identifier.of("superbridging", "hud/active_bridge_cursor");
	private static final int CANDIDATE_OVERLAY_SIZE = 15;
	private static final int ACTIVE_OVERLAY_WIDTH = 19;
	private static final int ACTIVE_OVERLAY_HEIGHT = 15;

	@Inject(method = "renderCrosshair", at = @At("TAIL"))
	private void superbridging$decorateCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		if (client == null || client.options == null || client.options.hudHidden) {
			return;
		}

		BridgingController controller = BridgingController.getInstance();
		boolean bridgingActive = controller.isBridging();
		boolean startCandidate = !bridgingActive && controller.hasStartCandidate(client);

		if (!bridgingActive && !startCandidate) {
			return;
		}

		if (startCandidate) {
			drawCandidateGuide(context);
		}
		if (bridgingActive) {
			drawBridgingGuide(context);
		}
	}

	private void drawCandidateGuide(DrawContext context) {
		int originX = (context.getScaledWindowWidth() - CANDIDATE_OVERLAY_SIZE) / 2;
		int originY = (context.getScaledWindowHeight() - CANDIDATE_OVERLAY_SIZE) / 2;
		context.drawGuiTexture(RenderPipelines.CROSSHAIR, BRIDGE_READY_TEXTURE, originX, originY, CANDIDATE_OVERLAY_SIZE, CANDIDATE_OVERLAY_SIZE);
	}

	private void drawBridgingGuide(DrawContext context) {
		int originX = (context.getScaledWindowWidth() - ACTIVE_OVERLAY_WIDTH) / 2;
		int originY = (context.getScaledWindowHeight() - ACTIVE_OVERLAY_HEIGHT) / 2;
		context.drawGuiTexture(RenderPipelines.CROSSHAIR, BRIDGE_ACTIVE_TEXTURE, originX, originY, ACTIVE_OVERLAY_WIDTH, ACTIVE_OVERLAY_HEIGHT);
	}
}
