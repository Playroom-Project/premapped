package premapped.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the Fabric distribution without loading any client-only classes. */
public final class PreMappedFabric implements ModInitializer {
    public static final String MOD_ID = "premapped";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Announces the foundation build; dedicated-server generation is intentionally deferred. */
    @Override
    public void onInitialize() {
        LOGGER.info("PreMapped Worldgen Foundation initialized (Minecraft 1.20.1)");
    }
}
