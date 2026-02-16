package twitch.command;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import twitch.TwitchStreamPlugin;
import twitch.model.StreamerInfo;
import twitch.service.StreamerManager;

import java.util.UUID;

public class TwitchLuckPermsJoinListener implements Listener {
    private final TwitchStreamPlugin plugin;
    private final StreamerManager streamerManager;

    public TwitchLuckPermsJoinListener(TwitchStreamPlugin plugin, StreamerManager streamerManager) {
        this.plugin = plugin;
        this.streamerManager = streamerManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String mcName = player.getName();
        StreamerInfo streamer = streamerManager.getStreamers().stream()
                .filter(s -> s.mcName.equalsIgnoreCase(mcName))
                .findFirst().orElse(null);
        LuckPerms luckPerms = plugin.getLuckPerms();
        if (luckPerms == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        if (streamer == null) {
            luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
                user.data().clear(node -> node instanceof InheritanceNode &&
                        ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                luckPerms.getUserManager().saveUser(user);
            });
            return;
        }

        Boolean isLive = streamerManager.getStreamerLiveStatus().get(streamer.twitchName.toLowerCase());
        luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
            if (Boolean.TRUE.equals(isLive)) {
                plugin.getLogger().info("[DEBUG] Выдаём группу " + plugin.getTwitchGroup() + " игроку UUID=" + uuid);
                user.data().add(InheritanceNode.builder(plugin.getTwitchGroup()).build());
            } else {
                plugin.getLogger().info("[DEBUG] Снимаем группу " + plugin.getTwitchGroup() + " с игрока UUID=" + uuid);
                user.data().clear(node -> node instanceof InheritanceNode &&
                        ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
            }
            luckPerms.getUserManager().saveUser(user);
        });
    }
}
