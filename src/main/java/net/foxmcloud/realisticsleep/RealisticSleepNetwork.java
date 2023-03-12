package net.foxmcloud.realisticsleep;

import codechicken.lib.packet.ICustomPacketHandler;
import codechicken.lib.packet.PacketCustom;
import codechicken.lib.packet.PacketCustomChannelBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.play.IClientPlayNetHandler;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.event.EventNetworkChannel;

public class RealisticSleepNetwork {
	public static final ResourceLocation CHANNEL = new ResourceLocation(RealisticSleep.MODID + ":network");
	public static EventNetworkChannel netChannel;

	public static final int C_SET_TIME = 1;

	public static void sendTimeUpdate(ServerPlayerEntity player, long dayTime) {
		PacketCustom packet = new PacketCustom(CHANNEL, C_SET_TIME);
		packet.writeLong(dayTime);
		packet.sendToPlayer(player);
	}

	public static void init() {
		netChannel = PacketCustomChannelBuilder.named(CHANNEL).assignClientHandler(() -> ClientPacketHandler::new).build();
	}

	public static class ClientPacketHandler implements ICustomPacketHandler.IClientPacketHandler {
		@Override
		public void handlePacket(PacketCustom packet, Minecraft mc, IClientPlayNetHandler handler) {
			switch (packet.getType()) {
				case (RealisticSleepNetwork.C_SET_TIME):
					mc.level.setDayTime(packet.readLong());
					break;
			}
		}
	}
}
