package net.foxmcloud.realisticsleep;

import java.util.UUID;

import codechicken.lib.packet.ICustomPacketHandler;
import codechicken.lib.packet.PacketCustom;
import codechicken.lib.packet.PacketCustomChannelBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.network.event.EventNetworkChannel;

public class RealisticSleepNetwork {
	public static final ResourceLocation CHANNEL = new ResourceLocation(RealisticSleep.MODID + ":network");
	public static EventNetworkChannel netChannel;

	public static final int C_SET_TIME = 1;
	public static final int S_WAKE_UP = 1;

	public static void sendTimeUpdate(ServerPlayer player, long dayTime) {
		PacketCustom packet = new PacketCustom(CHANNEL, C_SET_TIME);
		packet.writeLong(dayTime);
		packet.sendToPlayer(player);
	}
	
	public static void sendWakeUp(UUID id) {
		PacketCustom packet = new PacketCustom(CHANNEL, S_WAKE_UP);
		packet.writeUUID(id);
		packet.sendToServer();
	}

	public static void init() {
		netChannel = PacketCustomChannelBuilder.named(CHANNEL)
				.assignClientHandler(() -> ClientPacketHandler::new)
				.assignServerHandler(() -> ServerPacketHandler::new)
				.build();
	}

	public static class ClientPacketHandler implements ICustomPacketHandler.IClientPacketHandler {
		@Override
		public void handlePacket(PacketCustom packet, Minecraft mc, ClientPacketListener handler) {
			switch (packet.getType()) {
				case (RealisticSleepNetwork.C_SET_TIME):
					mc.level.setDayTime(packet.readLong());
					break;
			}
		}
	}
	
	public static class ServerPacketHandler implements ICustomPacketHandler.IServerPacketHandler {
	    @Override
	    public void handlePacket(PacketCustom packet, ServerPlayer sender, ServerGamePacketListenerImpl handler) {
	        switch (packet.getType()) {
	        case (RealisticSleepNetwork.S_WAKE_UP):
				RealisticSleep.externalWakeUp = packet.readUUID();
				break;
	        }
	    }
	}
}
