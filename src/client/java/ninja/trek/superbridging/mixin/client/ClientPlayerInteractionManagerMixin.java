package ninja.trek.superbridging.mixin.client;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import ninja.trek.superbridging.client.BridgingController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
abstract class ClientPlayerInteractionManagerMixin {
	@Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
	private void superbridging$preventNormalPlacement(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
		if (BridgingController.getInstance().shouldSuppressManualPlacement()) {
			cir.setReturnValue(ActionResult.FAIL);
			cir.cancel();
		}
	}
}
