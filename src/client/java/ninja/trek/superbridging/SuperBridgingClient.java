package ninja.trek.superbridging;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import ninja.trek.superbridging.client.BridgingController;

public class SuperBridgingClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(BridgingController.getInstance()::tick);
	}
}
