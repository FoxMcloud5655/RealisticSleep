package net.foxmcloud.realisticsleep;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.potion.EffectInstance;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.FoodStats;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
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
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.TickEvent.WorldTickEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
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
public class RealisticSleep {
	public static final Logger LOGGER = LogManager.getLogger("RealisticSleep");
	public static final String MODNAME = "Realistic Sleep";
	public static final String MODID = "realisticsleep";
	public static final String MODID_PROPER = "RealisticSleep";
	public static final String VERSION = "${mod_version}";
	public static final boolean DEBUG = true;
	public static final Method getLoadedChunksMethod;
	public static final Method tickBlockEntitiesMethod;
	private final RealisticSleepConfig config = new RealisticSleepConfig();

	public static final int ticksToSimulateDivider = 4;
	
	public static long lastSleepTicks = 0;
	public static long previousDayTime = 0;
	public static long previousClientSleepCounter = 0;
	public static int durationOfSkip = 0;
	public static UUID externalWakeUp = null;

	public RealisticSleep() {
		if (DEBUG) logInfo("Initial mod setup started.");
		ModLoadingContext.get().registerConfig(Type.SERVER, this.config.getSpec());
		FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onSetup);
		MinecraftForge.EVENT_BUS.register(this);
		RealisticSleepNetwork.init();
		if (DEBUG) logInfo("Initial mod setup complete.");
	}

	public void onSetup(FMLCommonSetupEvent event) {
		if (getLoadedChunksMethod == null) {
			logError("Chunk reflection unsuccessful.  Method 1 will not work.");
		}
		else {
			logInfo("Chunk reflection successful.");
			getLoadedChunksMethod.setAccessible(true);
		}
		if (tickBlockEntitiesMethod == null) {
			logError("World reflection unsuccessful.  Method 2 will not work.");
		}
		else {
			logInfo("World reflection successful.");
			tickBlockEntitiesMethod.setAccessible(true);
		}
	}

	@SubscribeEvent
	public void onServerTick(WorldTickEvent event) {
		if (event.world.isClientSide || event.world.dimension() != event.world.OVERWORLD || event.world.players().size() == 0) {
			return;
		}
		long ticksSkipped = event.world.getDayTime() < previousDayTime ? 24000 : event.world.getDayTime() - previousDayTime;
		if (ticksSkipped != 1) {
			previousDayTime = event.world.getDayTime();
			return;
		}
		if (durationOfSkip > 1) {
			durationOfSkip--;
			previousDayTime = event.world.getDayTime();
			return;
		}
		if (externalWakeUp != null) {
			List<ServerPlayerEntity> players = (List<ServerPlayerEntity>) event.world.players();
			for (int i = 0; i < players.size(); i++) {
				PlayerEntity player = players.get(i);
				if (player.getUUID().compareTo(externalWakeUp) == 0) {
					player.sleepCounter = 0;
				}
			}
			externalWakeUp = null;
		}

		// Check to see if any player on the server is sleeping.
		List<ServerPlayerEntity> players = (List<ServerPlayerEntity>) event.world.players();
		boolean arePlayersSleeping = false;
		boolean areAllPlayersSleeping = true;
		for (int i = 0; i < players.size(); i++) {
			if (players.get(i).sleepCounter > 99 - (config.getMaxTicksToSkip() / ticksToSimulateDivider)) {
				arePlayersSleeping = true;
			}
			else {
				areAllPlayersSleeping = false;
			}
		}
		
		if (arePlayersSleeping && config.isFullTick()) {
			for (int i = 0; i < players.size(); i++) {
				if (players.get(i).sleepCounter >= 99 - (config.getMaxTicksToSkip() / ticksToSimulateDivider)) {
					players.get(i).sleepCounter = 99 - (config.getMaxTicksToSkip() / ticksToSimulateDivider);
				}
			}
		}

		if (areAllPlayersSleeping) {
			if (DEBUG) logInfo("All players sleeping.  Checking if players have slept recently.");
			long ticksToSkip = getTicksToSkip(event.world);
			int ticksToSimulate = (int) (ticksToSkip * (1D / ticksToSimulateDivider));
			// Did the players sleep recently? If so, wake everyone up and reset the time back to what it was.
			if (!config.canSleep(event.world.getGameTime() - lastSleepTicks)) {
				if (DEBUG) logInfo("Players have slept recently.  Waking them up to prevent skipping too quickly.");
				players.forEach(player -> {
					if (player.sleepCounter > 0) {
						player.stopSleeping();
						player.displayClientMessage(new StringTextComponent("You're not that tired after recently sleeping."), true);
					}
				});
				((ServerWorldInfo)event.world.getLevelData()).setDayTime(previousDayTime);
				return;
			}
			if (DEBUG) logInfo("Good to go.  Running additional ticks.");
			switch (config.getSimulationMethod()) {
			case 1:
				tickTileEntities(ticksToSimulate, event.world); // TODO: Simulate random ticks.
				break;
			case 2:
				tickWorld(ticksToSimulate, event.world); // TODO: Simulate random ticks.
				break;
			case 3:
				durationOfSkip = config.getMaxTicksToWait();
				tickServer(ticksToSimulate, ticksToSkip, (ServerWorld)event.world);
				for (ServerPlayerEntity player : players) {
					RealisticSleepNetwork.sendTimeUpdate(player, event.world.getDayTime());
				}
				break;
			default:
				logError("Invalid configuration.  Please check the config file and use a defined method.");
				break;
			}
			players.forEach(player -> {
				player.displayClientMessage(new StringTextComponent("Time passes as everyone sleeps..."), true);
			});
			for (int i = 0; i < players.size(); i++) {
				ServerPlayerEntity player = (ServerPlayerEntity) players.get(i);
				if (players.get(i).getSleepTimer() > 0) {
					tickPotions(player, ticksToSkip);
					healPlayerFromFood(player, ticksToSimulate / config.getMaxTicksToSkip());
				}
			}
			boolean shouldWakeUp = true;
			if (config.isFullTick()) {
				if (ticksToSkip == config.getMaxTicksToSkip()) {
					shouldWakeUp = false;
				}
			}
			if (DEBUG) logInfo("Additional ticks ran.");
			if (shouldWakeUp) {
				lastSleepTicks = event.world.getGameTime();
				if (DEBUG) logInfo("No need to run any more additional ticks.  Waking up players.");
				players.forEach(player -> {
					if (player.getSleepTimer() > 0) {
						player.stopSleeping();
					}
				});
			}
		}
	}
	
	@OnlyIn(Dist.CLIENT)
	@SubscribeEvent
	public void checkPlayerWakeUp(ClientTickEvent event) {
		ClientPlayerEntity player = Minecraft.getInstance().player;
		if (player != null && player.sleepCounter + config.getMaxTicksToWait() < previousClientSleepCounter) {
			RealisticSleepNetwork.sendWakeUp(player.getUUID());
			previousClientSleepCounter = 0;
		}
		if (player != null && player.sleepCounter > previousClientSleepCounter) {
			previousClientSleepCounter = player.sleepCounter;
		}
	}

	private long getTicksToSkip(World world) {
		long maxTicksToSkip = 24000 - world.getDayTime() % 24000;
		long ticksToSkip = config.isFullTick() ? Math.min(config.getMaxTicksToSkip(), maxTicksToSkip) : maxTicksToSkip;
		if (DEBUG && world instanceof ServerWorld) logInfo("Calculated " + ticksToSkip + "/" + maxTicksToSkip + " ticks for time skip.");
		return ticksToSkip;
	}

	private void tickTileEntities(int ticksToSimulate, World world) {
		if (getLoadedChunksMethod != null) {
			ArrayList<ITickableTileEntity> tileEntities = new ArrayList<ITickableTileEntity>();
			ServerChunkProvider chunkProvider = (ServerChunkProvider)world.getChunkSource();
			try {
				Iterable<ChunkHolder> chunks = (Iterable<ChunkHolder>)getLoadedChunksMethod.invoke(chunkProvider.chunkMap);
				chunks.forEach(chunkHolder -> {
					Chunk chunk = chunkHolder.getTickingChunk();
					if (chunk == null) return;
					chunk.getBlockEntitiesPos().forEach(tileEntityPos -> {
						TileEntity tileEntity = chunk.getBlockEntity(tileEntityPos);
						if (tileEntity instanceof ITickableTileEntity && config.tileEntityNotInBlacklist(tileEntity)) {
							if (DEBUG) logInfo("Found TileEntity " + tileEntity.getType().getRegistryName() + " to update.");
							tileEntities.add((ITickableTileEntity)tileEntity);
						}
					});
				});
			}
			catch (Exception e) {
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
					tickBlockEntitiesMethod.invoke(world); // TODO: Test NOT using reflection.
				}
			}
			catch (Exception e) {
				logError("Error when calling tickBlockEntities: " + e.getMessage());
			}
		}
		else logError("World reflection not valid with current setup.");
	}

	private void tickServer(int ticksToSimulate, long ticksToSkip, ServerWorld world) {
		for (int i = 0; i < ticksToSimulate; i++) {
			world.tick(() -> false);
		}
		((ServerWorldInfo)world.getLevelData()).setDayTime(previousDayTime + ticksToSkip);
	}

	public void tickPotions(ServerPlayerEntity player, long ticksElapsed) {
		Collection<EffectInstance> activeEffects = player.getActiveEffects();
		activeEffects.forEach(effect -> {
			for (int i = 0; i < ticksElapsed; i++) effect.tick(player, () -> {});
		});
	}

	public void healPlayerFromFood(ServerPlayerEntity player, float max) {
		float healthToHeal = Math.max(player.getMaxHealth() - player.getHealth(), max);
		float hungerToHealthRatio = config.hungerDrainedToHeal();
		float hunger = healthToHeal * hungerToHealthRatio;
		FoodStats playerFood = player.getFoodData();
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

	public void registerCommand(CommandDispatcher<CommandSource> dispatcher) {
		dispatcher.register(
			Commands.literal("realisticsleep")
				.then(Commands.literal("dumpPlayerEntityFields").executes(ctx -> dumpPlayerEntityFields(ctx.getSource()))
				)
				.then(Commands.literal("getSleepCounter").executes(this::getSleepCounter)
					.then(Commands.argument("player", EntityArgument.player()).executes(this::getSleepCounter))
				)
				.then(Commands.literal("setHP").executes(this::setHP)
					.then(Commands.argument("entities", EntityArgument.entities()).executes(this::setHP)
						.then(Commands.argument("hp", FloatArgumentType.floatArg()).executes(this::setHP)))
				)
			.executes(ctx -> versionOfMod(ctx.getSource()))
		);
	}

	private int dumpPlayerEntityFields(CommandSource source) {
		Field[] playerFields = ServerPlayerEntity.class.getSuperclass().getDeclaredFields();
		String fieldNames = "";
		for (int i = 0; i < playerFields.length; i++) {
			fieldNames += playerFields[i].getName() + (i < playerFields.length - 1 ? "\n" : "");
		}
		source.sendSuccess(new StringTextComponent(fieldNames), false);
		return 0;
	}

	private int getSleepCounter(CommandContext<CommandSource> commandContext) throws CommandSyntaxException {
		Entity entity;
		try {
			entity = EntityArgument.getEntity(commandContext, "player");
		}
		catch (Exception e) {
			entity = commandContext.getSource().getEntity();
		}
		if (entity != null && entity instanceof PlayerEntity) {
			PlayerEntity player = (PlayerEntity)entity;
			commandContext.getSource().sendSuccess(new StringTextComponent(player.getDisplayName().getString() + " has a sleepCounter of " + player.sleepCounter + ".").withStyle(TextFormatting.YELLOW), false);
			return 1;
		}
		commandContext.getSource().sendFailure(new StringTextComponent(entity == null ? "Entity provided (or you, if no another entity wasn't specified) was null, somehow." : "Selected entity is not a player.").withStyle(TextFormatting.RED));
		return 0;
	}
	
	private int setHP(CommandContext<CommandSource> commandContext) throws CommandSyntaxException {
		ArrayList<Entity> entities = new ArrayList<Entity>();
		float hp = 1;
		try {
			EntityArgument.getEntities(commandContext, "entities").forEach(entity -> {
				entities.add(entity);
			});;
		}
		catch (Exception e) {
			entities.clear();
			entities.add(commandContext.getSource().getEntity());
		}
		try {
			hp = FloatArgumentType.getFloat(commandContext, "hp");
		}
		catch (Exception e) {}
		int targetsAffected = 0;
		String textToSend = "No targets were valid.";
		if (entities != null && entities.size() > 0) {
			for (Entity entity : entities) {
				if (entity instanceof LivingEntity) {
					targetsAffected++;
					LivingEntity livingEntity = (LivingEntity)entity;
					livingEntity.setHealth(hp);
					textToSend = entity.getDisplayName().getString() + " set to " + livingEntity.getHealth() + " HP.";
				}
			}
			if (targetsAffected == 0) {
				commandContext.getSource().sendFailure(new StringTextComponent(textToSend).withStyle(TextFormatting.RED));
			}
			else if (targetsAffected > 1) {
				commandContext.getSource().sendSuccess(new StringTextComponent(targetsAffected + " entities set to " + hp + " HP.").withStyle(TextFormatting.YELLOW), true);
			}
			else {
				commandContext.getSource().sendSuccess(new StringTextComponent(textToSend).withStyle(TextFormatting.YELLOW), true);
			}
			return targetsAffected;
		}
		commandContext.getSource().sendFailure(new StringTextComponent("No targets selected.").withStyle(TextFormatting.RED));
		return 0;
	}

	private int versionOfMod(CommandSource source) {
		source.sendSuccess(new StringTextComponent("Realistic Sleep is using method " + config.getSimulationMethod() + " and is on version " + VERSION + ".").withStyle(TextFormatting.YELLOW), true);
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
			return ObfuscationReflectionHelper.findMethod(ChunkManager.class, "func_223491_f");
		}
		catch (Exception e) {
			logError("Exception occurred while accessing getLoadedChunksIterable: " + e.getMessage());
			return null;
		}
	}

	private static Method fetchTickBlockEntitiesMethod() {
		try {
			return ObfuscationReflectionHelper.findMethod(World.class, "func_217391_K");
		}
		catch (Exception e) {
			logError("Exception occurred while accessing tickBlockEntities: " + e.getMessage());
			return null;
		}
	}

	static {
		getLoadedChunksMethod = fetchGetLoadedChunksMethod();
		tickBlockEntitiesMethod = fetchTickBlockEntitiesMethod();
	}
}
