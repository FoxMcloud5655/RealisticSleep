package net.foxmcloud.realisticsleep;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ChunkManager;
import net.minecraft.world.server.ServerChunkProvider;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.SleepingLocationCheckEvent;
import net.minecraftforge.eventbus.api.Event.Result;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;


// A lot of the chunk handling code is based off of this post: https://forums.minecraftforge.net/topic/94589-116-how-to-get-loaded-chunks/
// Formatting and basic code structure was ripped off of Draconic Additions, which in turn was ripped off of Draconic Evolution.

@Mod(RealisticSleep.MODID)
public class RealisticSleep
{
	public static final Logger LOGGER = LogManager.getLogger("RealisticSleep");
	public static final String MODNAME = "Realistic Sleep";
	public static final String MODID = "realisticsleep";
	public static final String MODID_PROPER = "RealisticSleep";
	public static final String VERSION = "${mod_version}";
	public static final boolean DEBUG = true;
	public static final Method getLoadedChunksMethod;
	public static final Method tickBlockEntitiesMethod;
	private final RealisticSleepConfig config = new RealisticSleepConfig();
	
	public long lastSleepTicks = 0;

	public RealisticSleep() {
		ModLoadingContext.get().registerConfig(Type.COMMON, this.config.getSpec());
		FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onSetup);
		MinecraftForge.EVENT_BUS.register(this);
	}

	public void onSetup(FMLCommonSetupEvent event) {
		if (getLoadedChunksMethod == null) {
			logError("Chunk reflection unsuccessful.  Mod will not work.");
			return;
		}
		else {
			logInfo("Chunk reflection successful.");
			getLoadedChunksMethod.setAccessible(true);
		}
		if (tickBlockEntitiesMethod == null) {
			logError("World reflection unsuccessful.  Mod will not work.");
			return;
		}
		else {
			logInfo("World reflection successful.");
			tickBlockEntitiesMethod.setAccessible(true);
		}
	}

	@SubscribeEvent
	public void onPlayerSleep(SleepingLocationCheckEvent event) {
		if (event.getEntityLiving().world.isRemote) {
			return;
		}
		if (event.getEntityLiving() instanceof ServerPlayerEntity) {
			ServerPlayerEntity player = (ServerPlayerEntity)event.getEntityLiving();
			World world = player.getEntityWorld();
			if (player.isPlayerFullyAsleep()) {
				if (!config.canSleep(world.getGameTime() - lastSleepTicks)) {
					event.setResult(Result.DENY);
					player.sendStatusMessage(new StringTextComponent("You're not that tired after recently sleeping."), true);
					return;
				}
				long ticksUntilMorning = 24000 - world.getDayTime();
				if (DEBUG) logInfo("Calculated " + ticksUntilMorning + " ticks until morning.");
				int ticksToSimulate = (int)(ticksUntilMorning / 4);
				switch(config.getSimulationMethod()) {
				case 1:
					tickTileEntities(ticksToSimulate, world);
					break;
				case 2:
					tickWorld(ticksToSimulate, world);
					break;
				default:
					logError("Invalid configuration.  Please check the config file and use a defined method.");
					break;
				}
				lastSleepTicks = world.getGameTime();
				if (DEBUG) logInfo("Tick simulation complete.");
				event.setResult(Result.DEFAULT);
			}
		}
	}

	private void tickTileEntities(int ticksToSimulate, World world) {
		if (getLoadedChunksMethod != null) {
			ArrayList<ITickableTileEntity> tileEntities = new ArrayList<ITickableTileEntity>();
			ServerChunkProvider chunkProvider = (ServerChunkProvider)world.getChunkProvider();
			try {
				Iterable<ChunkHolder> chunks = (Iterable<ChunkHolder>)getLoadedChunksMethod.invoke(chunkProvider.chunkManager);
				chunks.forEach(chunkHolder -> {
					Chunk chunk = chunkHolder.getChunkIfComplete();
					if (chunk == null) return;
					chunk.getTileEntitiesPos().forEach(tileEntityPos -> {
						TileEntity tileEntity = chunk.getTileEntity(tileEntityPos);
						if (tileEntity instanceof ITickableTileEntity && config.tileEntityNotInBlacklist(tileEntity)) {
							if (DEBUG) logInfo("Found TileEntity " + tileEntity.getType().getRegistryName() + " to update.");
							tileEntities.add((ITickableTileEntity)tileEntity);
						}
					});
				});
			} catch (Exception e) {
				logError("Error when adding TileEntity to list: " + e.getMessage());
				return;
			}
			if (DEBUG) logInfo("Finished gathering TileEntities.  Simulating " + ticksToSimulate + " ticks...");
			tileEntities.forEach(tileEntity -> {
				for (int i = 0; i < ticksToSimulate; i++) {
					try {
						tileEntity.tick();
					}
					catch (Exception e) {
						logError("Error on TickEvent Handler: " + e.getMessage());
						return;
					}
				}	
			});
		}
		else logError("Chunk reflection not valid with current setup.");
	}
	
	private void tickWorld(int ticksToSimulate, World world) {
		if (tickBlockEntitiesMethod != null) {
			try {
				for (int i = 0; i < ticksToSimulate; i++) {
					tickBlockEntitiesMethod.invoke(world);
				}
			}
			catch (Exception e) {
				logError("Error when calling tickBlockEntities: " + e.getMessage());
			}
		}
		else logError("Wrold reflection not valid with current setup.");
	}
	
	private static void logInfo(String message) {
		LOGGER.info("[" + RealisticSleep.MODID_PROPER + "] " + message);
	}
	
	private static void logError(String message) {
		LOGGER.error("[" + RealisticSleep.MODID_PROPER + "] " + message);
	}

	private static Method fetchGetLoadedChunksMethod() {
		try {
			return ObfuscationReflectionHelper.findMethod(ChunkManager.class, "func_223491_f");
		} catch (Exception e) {
			logError("Exception occurred while accessing getLoadedChunksIterable: " + e.getMessage());
			return null;
		}
	}
	
	private static Method fetchTickBlockEntitiesMethod() {
		try {
			return ObfuscationReflectionHelper.findMethod(World.class, "func_217391_K");
		} catch (Exception e) {
			logError("Exception occurred while accessing tickBlockEntities: " + e.getMessage());
			return null;
		}
	}

	static {
		getLoadedChunksMethod = fetchGetLoadedChunksMethod();
		tickBlockEntitiesMethod = fetchTickBlockEntitiesMethod();
	}
}
