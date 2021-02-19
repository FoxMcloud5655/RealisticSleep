package net.foxmcloud.realisticsleep;

import java.util.List;

import com.google.common.base.Splitter;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.common.ForgeConfigSpec.LongValue;

public class RealisticSleepConfig {

	private ForgeConfigSpec spec;
	private IntValue method;
	private LongValue waitTime;
	private ConfigValue<String> blacklist;

	public RealisticSleepConfig() {
		final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
		builder.comment("Defines what method of tick simulation to perform.  The default is 1.\n"
				+ "  1 - Search and Tick (speed over precision)\n"
				+ "  2 - Full World Tick (precision over speed)");
		this.method = builder.defineInRange("method", 1, 1, 2);
		builder.comment("Waiting time before players are allowed to sleep again and skip time in seconds.");
		this.waitTime = builder.defineInRange("waitTime", 250L, 5L, 600L);
		builder.comment("A blacklist of what TileEntities that the loaded chunks method (method 1) will skip.");
		this.blacklist = builder.define("blacklist", "minecraft:chest,minecraft:mob_spawner,quark:monster_box,mana-and-artifice:magelight,iceandfire:ghost_chest,minecraft:skull,mana-and-artifice:inscription_table_tile_entity,iceandfire:iaf_lectern,computercraft:speaker,computercraft:computer_advanced,computercraft:computer,mana-and-artifice:slipstream_generator,minecraft:trapped_chest");
		this.spec = builder.build();
	}

	public ForgeConfigSpec getSpec() {
		return this.spec;
	}
	
	public int getSimulationMethod() {
		return this.method.get();
	}
	
	public boolean canSleep(long ticksElapsed) {
		return ticksElapsed >= this.waitTime.get() * 20L;
	}

	public boolean tileEntityNotInBlacklist(TileEntity tileEntity) {
		List<String> entries = Splitter.on(',').omitEmptyStrings().splitToList(this.blacklist.get());
		for (int i = 0; i < entries.size(); i++) {
			if (tileEntity.getType().getRegistryName().toString().equalsIgnoreCase(entries.get(i)))
				return false;
		}
		return true;
	}
}