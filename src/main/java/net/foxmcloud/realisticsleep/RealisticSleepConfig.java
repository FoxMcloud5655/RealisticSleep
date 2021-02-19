package net.foxmcloud.realisticsleep;

import java.util.List;

import com.google.common.base.Splitter;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

public class RealisticSleepConfig {

	private ForgeConfigSpec spec;
	private ConfigValue<String> blacklist;

	public RealisticSleepConfig() {
		final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
		this.blacklist = builder.define("blacklist", "minecraft:chest,minecraft:mob_spawner,quark:monster_box,mana-and-artifice:magelight,iceandfire:ghost_chest,minecraft:skull,mana-and-artifice:inscription_table_tile_entity,iceandfire:iaf_lectern,computercraft:speaker,computercraft:computer_advanced,computercraft:computer,mana-and-artifice:slipstream_generator,minecraft:trapped_chest");
		this.spec = builder.build();
	}

	public ForgeConfigSpec getSpec() {
		return this.spec;
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