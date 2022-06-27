package net.foxmcloud.realisticsleep;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.TickEvent.WorldTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

// A lot of the chunk handling code is based off of this post: https://forums.minecraftforge.net/topic/94589-116-how-to-get-loaded-chunks/
// Formatting and basic code structure was ripped off of Draconic Additions, which in turn was ripped off of Draconic Evolution.

@Mod(RealisticSleep.MODID)
public class RealisticSleep {
	public static final Logger LOGGER = LogManager.getLogger("RealisticSleep");
	public static final String MODNAME = "Realistic Sleep";
	public static final String MODID = "realisticsleep";
	public static final String MODID_PROPER = "RealisticSleep";
	public static final String VERSION = "${mod_version}";
	public static final boolean DEBUG = false;
	public static final Method getLoadedChunksMethod;
	public static final Method tickBlockEntitiesMethod;
	public static final Field sleepTimerField;
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
		if (sleepTimerField == null) {
			logError("Sleep Timer reflection unsuccessful.  Method 3 will not work.");
			return;
		}
		else {
			logInfo("Sleep Timer reflection successful.");
			sleepTimerField.setAccessible(true);
		}
	}

	@SubscribeEvent
	public void onServerTick(WorldTickEvent event) {
		// If we aren't in the Overworld or if we're giving the server a break from skipping, just do nothing.
		if (event.world.dimension() != event.world.OVERWORLD || event.world.isClientSide || event.world.getGameTime() % config.getMaxTicksToWait() != 0 || currentlySkipping || event.world.players().size() == 0) {
			return;
		}

		// Total amount of ticks passed since last update.
		long ticksSkipped = event.world.getDayTime() < previousDayTime ? 24000 : 0 + event.world.getDayTime() - previousDayTime;
		// Check to see if any player on the server is sleeping.
		List<ServerPlayer> players = (List<ServerPlayer>) event.world.players();
		boolean arePlayersSleeping = false;
		boolean areAllPlayersSleeping = true;
		for (int i = 0; i < players.size(); i++) {
			if (players.get(i).getSleepTimer() > 95) {
				arePlayersSleeping = true;
			}
			else {
				areAllPlayersSleeping = false;
			}
		}

		if (!config.isFullTick()) {
			// Has there been a time skip? If not, then update time tracker and do nothing.
			if (!config.isTimeSkip(ticksSkipped)) {
				previousDayTime = event.world.getDayTime();
				return;
			}
		}
		else if (arePlayersSleeping) {
			try {
				for (int i = 0; i < players.size(); i++) {
					if (sleepTimerField.getInt(players.get(i)) > 95) {
						sleepTimerField.setInt(players.get(i), 95);
					}
				}
			}
			catch (Exception e) {
				logError("Exception occurred while accessing " + e.getMessage() + ".");
				return;
			}
		}

		if (areAllPlayersSleeping) {
			long ticksToSkip = getTicksToSkip(event.world, previousDayTime);
			int ticksToSimulate = (int) (ticksToSkip * 0.25D);
			// Did the players sleep recently? If so, wake everyone up and reset the time back to what it was.
			if (!config.canSleep(event.world.getGameTime() - lastSleepTicks)) {
				players.forEach(player -> {
					if (player.getSleepTimer() > 0) {
						player.stopSleeping();
						player.displayClientMessage(Component.literal("You're not that tired after recently sleeping."), true);
					}
				});
				((ServerLevelData)event.world.getLevelData()).setDayTime(previousDayTime);
				return;
			}
			currentlySkipping = true;
			switch (config.getSimulationMethod()) {
			case 1:
				tickTileEntities(ticksToSimulate, event.world); // TODO: Simulate random ticks.
				break;
			case 2:
				tickWorld(ticksToSimulate, event.world); // TODO: Simulate random ticks.
				break;
			case 3:
				tickServer(ticksToSimulate, (ServerLevel)event.world);
				((ServerLevelData)event.world.getLevelData()).setDayTime(previousDayTime + ticksToSkip);
				break;
			default:
				logError("Invalid configuration.  Please check the config file and use a defined method.");
				break;
			}
			players.forEach(player -> {
				player.displayClientMessage(Component.literal("Time passes as everyone sleeps..."), true);
			});
			for (int i = 0; i < players.size(); i++) {
				ServerPlayer player = (ServerPlayer) players.get(i);
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
						player.stopSleeping();
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
			ClientLevel world = (ClientLevel)event.player.getCommandSenderWorld();
			List<AbstractClientPlayer> players = world.players(); // All players in range of the player?
			boolean allPlayersSleeping = true;
			for (int i = 0; i < players.size(); i++) {
				if (players.get(i).getSleepTimer() < 95) {
					allPlayersSleeping = false;
				}
			}
			if (config.isFullTick() && allPlayersSleeping) {
				world.setDayTime(previousClientDayTime + (getTicksToSkip(world, previousClientDayTime) / config.getMaxTicksToWait()));
			}
			previousClientDayTime = world.getDayTime();
		}
	}

	@SuppressWarnings("unused")
	private long getTicksToSkip(Level world, long previousTime) {
		long ticksSkipped = world.getDayTime() < previousTime ? 24000 : 0 + world.getDayTime() - previousTime;
		long ticksToSkip = config.isTimeSkip(ticksSkipped) ? ticksSkipped : 24000 - world.getDayTime();
		if (DEBUG && world instanceof ServerLevel) logInfo("Calculated " + ticksToSkip + " ticks for time skip.");
		return config.isFullTick() ? Math.min(ticksToSkip, config.getMaxTicksToSkip()) : ticksToSkip;
	}

	private void tickTileEntities(int ticksToSimulate, Level world) {
		if (getLoadedChunksMethod != null) {
			ArrayList<TickingBlockEntity> tileEntities = new ArrayList<TickingBlockEntity>();
			ChunkSource chunkSource = (ChunkSource)world.getChunkSource();
			try {
				Iterable<ChunkHolder> chunks = (Iterable<ChunkHolder>)getLoadedChunksMethod.invoke(chunkSource); //TODO: NOT CORRECT
				chunks.forEach(chunkHolder -> {
					LevelChunk chunk = chunkHolder.getTickingChunk();
					if (chunk == null) return;
					chunk.getBlockEntitiesPos().forEach(tileEntityPos -> {
						BlockEntity tileEntity = chunk.getBlockEntity(tileEntityPos);
						if (tileEntity instanceof TickingBlockEntity && config.tileEntityNotInBlacklist(tileEntity)) {
							if (DEBUG) logInfo("Found BlockEntity " + BlockEntityType.getKey(tileEntity.getType()) + " to update.");
							tileEntities.add((TickingBlockEntity)tileEntity);
						}
					});
				});
			}
			catch (Exception e) {
				logError("Error when adding BlockEntity to list: " + e.getMessage());
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

	private void tickWorld(int ticksToSimulate, Level world) {
		if (tickBlockEntitiesMethod != null) {
			try {
				for (int i = 0; i < ticksToSimulate; i++) {
					tickBlockEntitiesMethod.invoke(world); // TODO: Test NOT using reflection.
				}
			}
			catch (Exception e) {
				logError("Error when calling tickBlockEntities: " + e.getMessage());
			}
		}
		else logError("World reflection not valid with current setup.");
	}

	private void tickServer(int ticksToSimulate, ServerLevel world) {
		try {
			for (int i = 0; i < ticksToSimulate; i++) {
				world.tick(() -> true);
			}
		}
		catch (Exception e) {
			logError("Error when ticking world: " + e.getMessage());
		}
	}

	public void tickPotions(ServerPlayer player, long ticksElapsed) {
		Collection<MobEffectInstance> activeEffects = player.getActiveEffects();
		activeEffects.forEach(effect -> {
			for (int i = 0; i < ticksElapsed; i++) effect.tick(player, () -> {});
		});
	}

	public void healPlayerFromFood(ServerPlayer player, float max) {
		float healthToHeal = Math.max(player.getMaxHealth() - player.getHealth(), max);
		float hungerToHealthRatio = config.hungerDrainedToHeal();
		float hunger = healthToHeal * hungerToHealthRatio;
		FoodData playerFood = player.getFoodData();
		if (playerFood.getSaturationLevel() > 0 && hunger > 0) {
			float saturationToDrain = Math.min(playerFood.getSaturationLevel(), hunger);
			playerFood.setSaturation(playerFood.getSaturationLevel() - saturationToDrain);
			hunger -= saturationToDrain;
		}
		if (hunger > 0) {
			int foodToDrain = (int)Math.min(playerFood.getFoodLevel() - config.hungerLimit(), hunger);
			playerFood.setFoodLevel(playerFood.getFoodLevel() - foodToDrain);
			hunger -= foodToDrain;
		}
		player.heal(healthToHeal - (hunger / hungerToHealthRatio));
	}

	@SubscribeEvent
	public void registerCommandsEvent(RegisterCommandsEvent event) {
		registerCommand(event.getDispatcher());
	}
	
    public void registerCommand(CommandDispatcher<CommandSourceStack> commandDispatcher) {
        commandDispatcher.register(
                Commands.literal("realisticsleep")
                        .then(Commands.literal("dumpPlayerFields")
                                .executes(ctx -> dumpPlayerFields(ctx.getSource()))
                        )
                        .then(Commands.literal("hasSleepTimer")
                        		.executes(ctx -> hasSleepTimer(ctx.getSource()))
                        )
                        .executes(ctx -> versionOfMod(ctx.getSource()))
        );
    }

	private int dumpPlayerFields(CommandSourceStack commandSourceStack) {
		Field[] playerFields = ServerPlayer.class.getSuperclass().getDeclaredFields();
		String fieldNames = "";
		for (int i = 0; i < playerFields.length; i++) {
			fieldNames += playerFields[i].getName() + (i < playerFields.length - 1 ? "\n" : "");
		}
		commandSourceStack.sendSuccess(Component.literal(fieldNames), DEBUG);
		return 0;
	}

	private int hasSleepTimer(CommandSourceStack commandSourceStack) {
		commandSourceStack.sendSuccess(Component.literal(Boolean.toString(sleepTimerField != null)), DEBUG);
		return 0;
	}

	private int versionOfMod(CommandSourceStack commandSourceStack) {
		commandSourceStack.sendSuccess(Component.literal("Realistic Sleep is using method " + config.getSimulationMethod() + "and is on version " + VERSION + "."), true);
		return 0;
	}

	private static void logInfo(String message) {
		LOGGER.info("[" + RealisticSleep.MODID_PROPER + "] " + message);
	}

	private static void logError(String message) {
		LOGGER.error("[" + RealisticSleep.MODID_PROPER + "] " + message);
	}

	private static Method fetchGetLoadedChunksMethod() {
		try {
			return ObfuscationReflectionHelper.findMethod(ChunkMap.class, "getChunks");
		}
		catch (Exception e) {
			logError("Exception occurred while accessing getLoadedChunksIterable: " + e.getMessage());
			return null;
		}
	}

	private static Method fetchTickBlockEntitiesMethod() {
		try {
			return ObfuscationReflectionHelper.findMethod(Level.class, "tickBlockEntities");
		}
		catch (Exception e) {
			logError("Exception occurred while accessing tickBlockEntities: " + e.getMessage());
			return null;
		}
	}

	private static Field fetchSleepTimerField() {
		try {
			return ObfuscationReflectionHelper.findField(Player.class, "sleepCounter");
		}
		catch (Exception e) {
			logError("Exception occurred while accessing sleepTimer: " + e.getMessage());
			return null;
		}
	}

	static {
		getLoadedChunksMethod = fetchGetLoadedChunksMethod();
		tickBlockEntitiesMethod = fetchTickBlockEntitiesMethod();
		sleepTimerField = fetchSleepTimerField();
	}
}
