package net.foxmcloud.realisticsleep;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.potion.EffectInstance;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.FoodStats;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ChunkManager;
import net.minecraft.world.server.ServerChunkProvider;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.ServerWorldInfo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.TickEvent.WorldTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
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
	public static final boolean DEBUG = false;
	public static final Method getLoadedChunksMethod;
	public static final Method tickBlockEntitiesMethod;
	private final RealisticSleepConfig config = new RealisticSleepConfig();
	
	public long lastSleepTicks = 0;
	public long previousDayTime = 0;
	public long previousClientDayTime = 0;
	public boolean currentlySkipping = false;

	public RealisticSleep() {
		ModLoadingContext.get().registerConfig(Type.SERVER, this.config.getSpec());
		FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onSetup);
		MinecraftForge.EVENT_BUS.register(this);
	}

	public void onSetup(FMLCommonSetupEvent event) {
		if (getLoadedChunksMethod == null) {
			logError("Chunk reflection unsuccessful.  Method 1 will not work.");
			return;
		}
		else {
			logInfo("Chunk reflection successful.");
			getLoadedChunksMethod.setAccessible(true);
		}
		if (tickBlockEntitiesMethod == null) {
			logError("World reflection unsuccessful.  Method 2 will not work.");
			return;
		}
		else {
			logInfo("World reflection successful.");
			tickBlockEntitiesMethod.setAccessible(true);
		}
	}

	@SubscribeEvent
	public void onServerTick(WorldTickEvent event) {
		// If we aren't in the Overworld or if we're giving the server a break from skipping, just do nothing.
		if (event.world.getDimensionKey() != event.world.OVERWORLD || event.world.getGameTime() % config.getMaxTicksToWait() != 0 || currentlySkipping || event.world.getPlayers().size() == 0) {
			return;
		}
		
		// Total amount of ticks passed since last update.
		long ticksSkipped = event.world.getDayTime() < previousDayTime ? 24000 : 0 +
				event.world.getDayTime() - previousDayTime;
		// Check to see if any player on the server is sleeping.
		List<ServerPlayerEntity> players = (List<ServerPlayerEntity>) event.world.getPlayers();
		boolean arePlayersSleeping = false;
		boolean areAllPlayersSleeping = true;
		for (int i = 0; i < players.size(); i++) {
			if (players.get(i).getSleepTimer() >= 95) {
				arePlayersSleeping = true;
			}
			else {
				areAllPlayersSleeping = false;
			}
		}
		
		if (!config.isFullTick()) {
			// Has there been a time skip?  If not, then update time tracker and do nothing.
			if (!config.isTimeSkip(ticksSkipped)) {
				previousDayTime = event.world.getDayTime();
				return;
			}
		}
		else if (arePlayersSleeping) {
			Class<?> playerClass = PlayerEntity.class;
			try {
				Field sleepTimer = playerClass.getDeclaredField("sleepTimer");
				sleepTimer.setAccessible(true);
				for (int i = 0; i < players.size(); i++) {
					if (players.get(i).getSleepTimer() >= 95) {
						sleepTimer.setInt(players.get(i), 95);
					}
				}
			}
			catch (Exception e) {
				logError("Exception occurred while accessing sleepTimer for PlayerEntity: " + e.getMessage());
				return;
			}
		}
		
		if (areAllPlayersSleeping) {
			long ticksToSkip = getTicksToSkip(event.world, previousDayTime);
			int ticksToSimulate = (int)(ticksToSkip * 0.25D);
			// Did the players sleep recently?  If so, wake everyone up and reset the time back to what it was.
			if (!config.canSleep(event.world.getGameTime() - lastSleepTicks)) {
				players.forEach(player -> {
					if (player.getSleepTimer() > 0) {
						player.wakeUp();
						player.sendStatusMessage(new StringTextComponent("You're not that tired after recently sleeping."), true);
					}
				});
				((ServerWorldInfo)event.world.getWorldInfo()).setDayTime(previousDayTime);
				return;
			}
			currentlySkipping = true;
			switch(config.getSimulationMethod()) {
			case 1:
				tickTileEntities(ticksToSimulate, event.world); //TODO: Simulate random ticks.
				break;
			case 2:
				tickWorld(ticksToSimulate, event.world); //TODO: Simulate random ticks.
				break;
			case 3:
				tickServer(ticksToSimulate, (ServerWorld)event.world);
				((ServerWorldInfo)event.world.getWorldInfo()).setDayTime(previousDayTime + ticksToSkip);
				break;
			default:
				logError("Invalid configuration.  Please check the config file and use a defined method.");
				break;
			}
			players.forEach(player -> {
				player.sendStatusMessage(new StringTextComponent("Time passes as everyone sleeps..."), true);
			});
			for (int i = 0; i < players.size(); i++) {
				ServerPlayerEntity player = (ServerPlayerEntity)players.get(i);
				if (players.get(i).getSleepTimer() > 0) {
					tickPotions(player, ticksToSkip);
					healPlayerFromFood(player, ticksToSimulate / 40);
				}
			}
			boolean shouldWakeUp = true;
			if (config.isFullTick()) {
				if (ticksToSkip == config.getMaxTicksToSkip()) {
					shouldWakeUp = false;
				}
			}
			if (shouldWakeUp) {
				lastSleepTicks = event.world.getGameTime();
				if (DEBUG) logInfo("Tick simulation complete.");
				players.forEach(player -> {
					if (player.getSleepTimer() > 0) {
						player.wakeUp();
					}
				});
			}
		}
		previousDayTime = event.world.getDayTime();
		currentlySkipping = false;
	}
	
	@OnlyIn(Dist.CLIENT)
	@SubscribeEvent
	public void onClientPlayerTick(PlayerTickEvent event) {
		// Client-only processing to make the sky not so jerky and more smooth if in Full Tick mode.
		if (event.side == LogicalSide.CLIENT) {
			ClientWorld world = (ClientWorld)event.player.getEntityWorld();
			List<AbstractClientPlayerEntity> players = world.getPlayers();  // All players in range of the player?
			boolean allPlayersSleeping = true;
			for (int i = 0; i < players.size(); i++) {
				if (players.get(i).getSleepTimer() <= 95) {
					allPlayersSleeping = false;
				}
			}
			if (config.isFullTick() && allPlayersSleeping) {
				world.setDayTime(previousDayTime + (getTicksToSkip(world, previousClientDayTime) / config.getMaxTicksToWait()));
			}
			previousClientDayTime = world.getDayTime();
		}
	}
	
	private long getTicksToSkip(World world, long previousTime) {
		long ticksSkipped = world.getDayTime() < previousTime ? 24000 : 0 + world.getDayTime() - previousTime;
		long ticksToSkip = config.isTimeSkip(ticksSkipped) ? ticksSkipped : 24000 - world.getDayTime();
		if (DEBUG && world instanceof ServerWorld) logInfo("Calculated " + ticksToSkip + " ticks for time skip.");
		return config.isFullTick() ? Math.min(ticksToSkip, config.getMaxTicksToSkip()) : ticksToSkip;
	}

	private void tickTileEntities(int ticksToSimulate, World world) {
		if (getLoadedChunksMethod != null) { // TODO: Test NOT using reflection.
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
						logError("Error when ticking tile entity: " + e.getMessage());
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
					tickBlockEntitiesMethod.invoke(world); //TODO: Test NOT using reflection.
				}
			}
			catch (Exception e) {
				logError("Error when calling tickBlockEntities: " + e.getMessage());
			}
		}
		else logError("World reflection not valid with current setup.");
	}

	private void tickServer(int ticksToSimulate, ServerWorld world) {
		try {
			for (int i = 0; i < ticksToSimulate; i++) {
				world.tick(() -> true);
			}
		}
		catch (Exception e) {
			logError("Error when ticking world: " + e.getMessage());
		}
	}

	public void tickPotions(ServerPlayerEntity player, long ticksElapsed) {
		Collection<EffectInstance> activeEffects = player.getActivePotionEffects();
		activeEffects.forEach(effect -> {
			for (int i = 0; i < ticksElapsed; i++)
				effect.tick(player, ()->{});
		});
	}
	
	public void healPlayerFromFood(ServerPlayerEntity player, float max) {
		float healthToHeal = Math.max(player.getMaxHealth() - player.getHealth(), max);
		float hungerToHealthRatio = config.hungerDrainedToHeal();
		float hunger = healthToHeal * hungerToHealthRatio;
		FoodStats playerFood = player.getFoodStats();
		if (playerFood.getSaturationLevel() > 0 && hunger > 0) {
			float saturationToDrain = Math.min(playerFood.getSaturationLevel(), hunger);
			playerFood.setFoodSaturationLevel(playerFood.getSaturationLevel() - saturationToDrain);
			hunger -= saturationToDrain;
		}
		if (hunger > 0) {
			int foodToDrain = (int)Math.min(playerFood.getFoodLevel() - config.hungerLimit(), hunger);
			playerFood.setFoodLevel(playerFood.getFoodLevel() - foodToDrain);
			hunger -= foodToDrain;
		}
		player.heal(healthToHeal - (hunger / hungerToHealthRatio));
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
