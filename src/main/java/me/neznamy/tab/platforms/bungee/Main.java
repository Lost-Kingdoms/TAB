package me.neznamy.tab.platforms.bungee;

import java.util.Collection;
import java.util.UUID;

import com.google.common.collect.Lists;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.Shared;
import me.neznamy.tab.shared.cpu.TabFeature;
import me.neznamy.tab.shared.cpu.UsageType;
import me.neznamy.tab.shared.features.BelowName;
import me.neznamy.tab.shared.features.PluginMessageHandler;
import me.neznamy.tab.shared.features.TabObjective;
import me.neznamy.tab.shared.packets.PacketPlayOutPlayerInfo;
import me.neznamy.tab.shared.packets.UniversalPacketPlayOut;
import me.neznamy.tab.shared.placeholders.Placeholders;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.protocol.packet.Login;
import net.md_5.bungee.protocol.packet.PlayerListItem;
import net.md_5.bungee.protocol.packet.Team;

/**
 * Main class for BungeeCord platform
 */
public class Main extends Plugin {

	//plugin message handler
	public static PluginMessageHandler plm;

	@SuppressWarnings("deprecation")
	@Override
	public void onEnable(){
		if (!isVersionSupported()) {
			ProxyServer.getInstance().getConsole().sendMessage("\u00a7c[TAB] The plugin requires BungeeCord build #1330 and up to work. Get it at https://ci.md-5.net/job/BungeeCord/");
			Shared.disabled = true;
			return;
		}
		ProtocolVersion.SERVER_VERSION = ProtocolVersion.values()[1];
		Shared.platform = new BungeeMethods(this);
		getProxy().getPluginManager().registerListener(this, new BungeeEventListener());
		if (getProxy().getPluginManager().getPlugin("PremiumVanish") != null) getProxy().getPluginManager().registerListener(this, new PremiumVanishListener());
		getProxy().getPluginManager().registerCommand(this, new Command("btab") {

			public void execute(CommandSender sender, String[] args) {
				if (Shared.disabled) {
					for (String message : Shared.disabledCommand.execute(args, sender.hasPermission("tab.reload"), sender.hasPermission("tab.admin"))) {
						sender.sendMessage(Placeholders.color(message));
					}
				} else {
					Shared.command.execute(sender instanceof ProxiedPlayer ? Shared.getPlayer(((ProxiedPlayer)sender).getUniqueId()) : null, args);
				}
			}
		});
		plm = new BungeePluginMessageHandler(this);
		Shared.load(true);
		BungeeMetrics.start(this);
	}
	
	/**
	 * Checks for compatibility and returns true if version is compatible, false if not
	 * @return true if version is compatible, false if not
	 */
	private boolean isVersionSupported() {
		try {
			Class.forName("net.md_5.bungee.protocol.packet.ScoreboardObjective$HealthDisplay");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
	
	@Override
	public void onDisable() {
		if (!Shared.disabled) {
			for (TabPlayer p : Shared.getPlayers()) p.getChannel().pipeline().remove(Shared.DECODER_NAME);
			Shared.unload();
		}
	}
	
	/**
	 * Injects custom channel duplex handler to prevent other plugins from overriding this one
	 * @param uuid - player's uuid
	 */
	public static void inject(UUID uuid) {
		Channel channel = Shared.getPlayer(uuid).getChannel();
		if (channel.pipeline().names().contains(Shared.DECODER_NAME)) channel.pipeline().remove(Shared.DECODER_NAME);
		channel.pipeline().addBefore("inbound-boss", Shared.DECODER_NAME, new ChannelDuplexHandler() {

			public void write(ChannelHandlerContext context, Object packet, ChannelPromise channelPromise) throws Exception {
				TabPlayer player = Shared.getPlayer(uuid);
				if (player == null) {
					super.write(context, packet, channelPromise);
					return;
				}
				try {
					if (packet instanceof PlayerListItem && player.getVersion().getMinorVersion() >= 8) {
						PacketPlayOutPlayerInfo info = UniversalPacketPlayOut.builder.readPlayerInfo(packet, player.getVersion());
						Shared.featureManager.onPacketPlayOutPlayerInfo(player, info);
						super.write(context, info.create(player.getVersion()), channelPromise);
						return;
					}
					if (Shared.featureManager.isFeatureEnabled("nametag16")) {
						long time = System.nanoTime();
						if (packet instanceof Team) {
							modifyPlayers((Team) packet);
							Shared.cpu.addTime(TabFeature.NAMETAGS, UsageType.ANTI_OVERRIDE, System.nanoTime()-time);
							super.write(context, packet, channelPromise);
							return;
						}
						if (packet instanceof ByteBuf && player.getVersion().getMinorVersion() >= 8) {
                            // No need to clone the packet, if this errors it doesn't get flushed anyway
							ByteBuf buf = ((ByteBuf) packet);
                            // Mark where the reader index is at
							int marker = buf.readerIndex();
							if (buf.readByte() == ((BungeeTabPlayer)player).getPacketId(Team.class)) {
								Team team = new Team();
								team.read(buf, null, ((ProxiedPlayer)player.getPlayer()).getPendingConnection().getVersion());
                                // From here on this buffers usage has ended, it needs to be released. Refer to
                                // io.netty.util.ReferenceCounted for an explanation.
                                // Side-note: Netty ByteBufs should always be read with try{} finally {buf.release()}
                                // to ensure proper freeing of the buffer all the time.
								buf.release();
								modifyPlayers(team);
								Shared.cpu.addTime(TabFeature.NAMETAGS, UsageType.ANTI_OVERRIDE, System.nanoTime()-time);
								super.write(context, team, channelPromise);
								return;
							}
                            // Reset the reader back if the condition doesnt end the method
							buf.readerIndex(marker);
						}
						Shared.cpu.addTime(TabFeature.NAMETAGS, UsageType.ANTI_OVERRIDE, System.nanoTime()-time);
					}
					if (packet instanceof Login) {
						//registering all teams again because client reset packet is sent
						Shared.cpu.runTaskLater(100, "Reapplying scoreboard components", TabFeature.WATERFALLFIX, UsageType.PACKET_READING, new Runnable() {

							@Override
							public void run() {
								if (Shared.featureManager.isFeatureEnabled("nametag16")) {
									for (TabPlayer all : Shared.getPlayers()) {
										all.registerTeam(player);
									}
								}
								TabObjective objective = (TabObjective) Shared.featureManager.getFeature("tabobjective");
								if (objective != null) {
									objective.onJoin(player);
								}
								BelowName belowname = (BelowName) Shared.featureManager.getFeature("belowname");
								if (belowname != null) {
									belowname.onJoin(player);
								}
							}
						});
					}
				} catch (Throwable e){
					Shared.errorManager.printError("An error occurred when analyzing packets for player " + player.getName() + " with client version " + player.getVersion().getFriendlyName(), e);
				}
				super.write(context, packet, channelPromise);
			}
		});
	}
	
	/**
	 * Removes all real players from packet if the packet doesn't come from TAB
	 * @param packet - packet to modify
	 */
	private static void modifyPlayers(Team packet){
		if (packet.getPlayers() == null) return;
		if (packet.getFriendlyFire() != 69) {
			Collection<String> col = Lists.newArrayList(packet.getPlayers());
			for (TabPlayer p : Shared.getPlayers()) {
				if (col.contains(p.getName()) && !Shared.featureManager.getNameTagFeature().isDisabledWorld(p.getWorldName())) {
					col.remove(p.getName());
				}
			}
			packet.setPlayers(col.toArray(new String[0]));
		}
	}
}