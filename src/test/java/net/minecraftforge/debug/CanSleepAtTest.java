package net.minecraftforge.debug;

import org.apache.logging.log4j.Logger;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.WorldProvider;

import net.minecraftforge.common.DimensionProvider;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.RegistryEvent;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod(modid = CanSleepAtTest.MODID, name = "CanSleepAtTest", version = "0.0.0", acceptableRemoteVersions = "*")
@Mod.EventBusSubscriber
public class CanSleepAtTest
{
    public static final String MODID = "can_sleep_at_test";
    public static final boolean ENABLED = false;
    public static DimensionProvider dim;
    public static DimensionType dimType = null;
    public static ResourceLocation dimId;
    private static Logger logger;

    @SubscribeEvent
    public void registerDimension(RegistryEvent.Register<DimensionProvider> event)
    {
        if(ENABLED)
        {
            dim = DimensionProvider.dimensionWithCustomType(MODID+":dimension", "CanSleepAtTest", "_cansleepattest", WorldProviderTest.class, false);
            dimType = dim.getType();
            dimId = dim.getID();
            event.getRegistry().register(dim);
            DimensionManager.registerDimensionActive(dimId);
        }
    }

    @Mod.EventHandler
    public void onPreInit(FMLPreInitializationEvent event)
    {
        if (ENABLED)
        {
            logger = event.getModLog();
            logger.info("Registered CanSleepAtTest dimension as DIM {}", dimId);
        }
    }

    public static class WorldProviderTest extends WorldProvider
    {
        @Override
        public DimensionType getDimensionType()
        {
            return CanSleepAtTest.dimType;
        }

        @Override
        public WorldSleepResult canSleepAt(EntityPlayer player, BlockPos pos)
        {
            // Creates a 5x5 blocks wide grid of the different sleep results
            return WorldSleepResult.values()[((pos.getX() / 5) + (pos.getZ() / 5)) % WorldSleepResult.values().length];
        }
    }
}
