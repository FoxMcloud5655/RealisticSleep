package net.foxmcloud.realisticsleep;

import java.util.List;

import com.google.common.base.Splitter;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.DoubleValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.common.ForgeConfigSpec.LongValue;

public class RealisticSleepConfig {

	private ForgeConfigSpec spec;
	private IntValue method;
	private IntValue maxTicksToSkip;
	private IntValue maxTicksToWait;
	private LongValue waitTime;
	private LongValue minTime;
	private BooleanValue healPlayers;
	private IntValue healCost;
	private DoubleValue hungerPerHour;
	private IntValue hungerLimit;
	private ConfigValue<String> blacklist;

	public RealisticSleepConfig() {
		final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
		builder.comment("Defines what method of tick simulation to perform.  The default is 1.\n"
				+ "  1 - Search and Tick - Speed over precision, retains vanilla behavior, best for large modpacks.\n"
				+ "  2 - World Tick - Precision over speed, retains vanilla behavior, best for medium modpacks.\n"
				+ "  3 - Full Server Tick - Extreme precision, quickly progress the night, works with all sizes of modpacks but is slow.");
		this.method = builder.defineInRange("method", 3, 1, 3);
		
		builder.comment("Defines how many ticks to skip forward per tick when using the Full Server Tick method.\n"
				+ "Must be in increments of 4 (preferrably in increments of 20).");
		this.maxTicksToSkip = builder.defineInRange("maxTicksToSkip", 40, 4, 100);
		
		builder.comment("Defines how many ticks to wait per tick when using the Full Server Tick method.\n"
				+ "A value of 2 would perform the skip every other tick (not recommended) and a value of 4 will perform a skip every 4th tick.");
		this.maxTicksToWait = builder.defineInRange("maxTicksToWait", 2, 1, 8);
		
		builder.comment("A blacklist of what TileEntities that the Search and Tick method (method 1) will skip.");
		this.blacklist = builder.define("blacklist", "minecraft:chest,minecraft:mob_spawner,quark:monster_box,mana-and-artifice:magelight,iceandfire:ghost_chest,minecraft:skull,mana-and-artifice:inscription_table_tile_entity,iceandfire:iaf_lectern,computercraft:speaker,computercraft:computer_advanced,computercraft:computer,mana-and-artifice:slipstream_generator,minecraft:trapped_chest");
		
		builder.comment("Waiting time before players are allowed to sleep again and skip time in seconds.");
		this.waitTime = builder.defineInRange("waitTime", 250L, 5L, 1200L);
		builder.comment("The minimum amount of time in seconds that must be skipped before it's considered for extra tick processing.");
		this.minTime = builder.defineInRange("minTime", 10L, 1L, 1200L);
		
		builder.comment("Defines if players that are sleeping are healed (if their hunger allows it) after waking up.");
		this.healPlayers = builder.define("healPlayers", true);
		builder.comment("Defines how much hunger it costs to heal 1/2 a heart (1 HP) if the above option is enabled.");
		this.healCost = builder.defineInRange("healCost", 2, 0, 10);
		
		builder.comment("Defines the amount of hunger that is taken away per minecraft hour slept (1000 ticks).\n"
				+ "This is a minimum value.  For example, if it took 4 hunger to heal while this amount was 10, it would use 10 hunger.\n"
				+ "However, if it took 14 to heal while this amount was 10, then the player would use 14 hunger.\n"
				+ "The maximum hours a player can normally sleep through the night is roughly 11.35.");
		this.hungerPerHour = builder.defineInRange("hungerPerHour", 1.0D, 0.0D, 4.0D);
		builder.comment("Defines the minimum amount of hunger that is always preserved so the player doesn't start dying of starvation upon waking up.");
		this.hungerLimit = builder.defineInRange("hungerLimit", 6, 0, 20);
		
		this.spec = builder.build();
	}

	public ForgeConfigSpec getSpec() {
		return this.spec;
	}
	
	public int getSimulationMethod() {
		return this.method.get();
	}
	
	public int getMaxTicksToSkip() {
		return this.maxTicksToSkip.get();
	}
	
	public int getMaxTicksToWait() {
		return this.maxTicksToWait.get();
	}
	
	public boolean tileEntityNotInBlacklist(TileEntity tileEntity) {
		List<String> entries = Splitter.on(',').omitEmptyStrings().splitToList(this.blacklist.get());
		for (int i = 0; i < entries.size(); i++) {
			if (tileEntity.getType().getRegistryName().toString().equalsIgnoreCase(entries.get(i)))
				return false;
		}
		return true;
	}
	
	public boolean canSleep(long ticksElapsed) {
		return ticksElapsed >= this.waitTime.get() * 20L;
	}
	
	public boolean isTimeSkip(long ticksSkipped) {
		return ticksSkipped > (this.minTime.get() * 20L);
	}
	
	public boolean canHeal() {
		return this.healPlayers.get();
	}
	
	public float hungerDrainedToHeal() {
		return this.healCost.get();
	}
	
	public float minHungerDrained(long ticksElapsed) {
		return (float)((ticksElapsed / 1000D) * this.hungerPerHour.get());
	}
	
	public int hungerLimit() {
		return this.hungerLimit.get();
	}
	
	public boolean isFullTick() {
		return this.method.get() == 3;
	}
}