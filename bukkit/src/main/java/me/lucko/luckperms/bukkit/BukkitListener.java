/*
 * Copyright (c) 2016 Lucko (Luck) <luck@lucko.me>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.bukkit;

import me.lucko.luckperms.bukkit.model.Injector;
import me.lucko.luckperms.bukkit.model.LPPermissible;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.constants.Message;
import me.lucko.luckperms.common.core.model.User;
import me.lucko.luckperms.common.utils.AbstractListener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class BukkitListener extends AbstractListener implements Listener {
    private final LPBukkitPlugin plugin;

    private final Set<UUID> deniedAsyncLogin = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> deniedLogin = new HashSet<>();

    private final Map<UUID, BukkitTask> cleanupTasks = Collections.synchronizedMap(new HashMap<>());

    BukkitListener(LPBukkitPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            deniedAsyncLogin.add(e.getUniqueId());
            return;
        }

        if (!plugin.isStarted() || !plugin.getStorage().isAcceptingLogins()) {
            deniedAsyncLogin.add(e.getUniqueId());

            // The datastore is disabled, prevent players from joining the server
            plugin.getLog().warn("The plugin storage is not loaded. Denying connection from: " + e.getUniqueId() + " - " + e.getName());
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Message.LOADING_ERROR.toString());
            return;
        }

        // remove any pending cleanup tasks
        BukkitTask task = cleanupTasks.remove(e.getUniqueId());
        if (task != null) {
            task.cancel();
        }

        // Process login
        onAsyncLogin(e.getUniqueId(), e.getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPreLoginMonitor(AsyncPlayerPreLoginEvent e) {
        // If they were denied before/at LOW, then don't bother handling here.
        if (deniedAsyncLogin.remove(e.getUniqueId())) {

            // this is a problem, as they were denied at low priority, but are now being allowed.
            if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                new IllegalStateException("Player connection was re-allowed for " + e.getUniqueId()).printStackTrace();
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "");
            }

            return;
        }

        // Login event was cancelled by another plugin
        if (plugin.isStarted() && plugin.getStorage().isAcceptingLogins() && e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            cleanupUser(e.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerLogin(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            deniedLogin.add(e.getPlayer().getUniqueId());
            return;
        }

        final Player player = e.getPlayer();
        final User user = plugin.getUserManager().get(plugin.getUuidCache().getUUID(player.getUniqueId()));

        if (user == null) {
            deniedLogin.add(e.getPlayer().getUniqueId());

            // User wasn't loaded for whatever reason.
            plugin.getLog().warn("User " + player.getUniqueId() + " - " + player.getName() + " could not be loaded. - denying login.");
            e.disallow(PlayerLoginEvent.Result.KICK_OTHER, Message.LOADING_ERROR.toString());
            return;
        }

        // remove any pending cleanup tasks
        BukkitTask task = cleanupTasks.remove(e.getPlayer().getUniqueId());
        if (task != null) {
            task.cancel();
        }

        try {
            // Make a new permissible for the user
            LPPermissible lpPermissible = new LPPermissible(player, user, plugin);

            // Inject into the player
            Injector.inject(player, lpPermissible);

        } catch (Throwable t) {
            t.printStackTrace();
        }

        plugin.refreshAutoOp(player);

        // We assume all users are not op, but those who are need extra calculation.
        if (player.isOp()) {
            plugin.doAsync(() -> user.getUserData().preCalculate(plugin.getPreProcessContexts(true)));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLoginMonitor(PlayerLoginEvent e) {
        // If they were denied before/at LOW, then don't bother handling here.
        if (deniedLogin.remove(e.getPlayer().getUniqueId())) {

            // this is a problem, as they were denied at low priority, but are now being allowed.
            if (e.getResult() == PlayerLoginEvent.Result.ALLOWED) {
                new IllegalStateException("Player connection was re-allowed for " + e.getPlayer().getUniqueId()).printStackTrace();
                e.disallow(PlayerLoginEvent.Result.KICK_OTHER, "");
            }

            return;
        }

        if (e.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            // The player got denied on sync login.
            cleanupUser(e.getPlayer().getUniqueId());
        } else {
            plugin.refreshAutoOp(e.getPlayer());
        }
    }

    // Wait until the last priority to unload, so plugins can still perform permission checks on this event
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent e) {
        final Player player = e.getPlayer();

        // Remove the custom permissible
        Injector.unInject(player, true, true);

        // Handle auto op
        if (plugin.getConfiguration().get(ConfigKeys.AUTO_OP)) {
            player.setOp(false);
        }

        // Call internal leave handling
        onLeave(player.getUniqueId());
    }

    private void cleanupUser(UUID uuid) {
        if (cleanupTasks.containsKey(uuid)) {
            return;
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            onLeave(uuid);
            cleanupTasks.remove(uuid);
        }, 60L);

        cleanupTasks.put(uuid, task);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (plugin.getConfiguration().get(ConfigKeys.OPS_ENABLED)) {
            return;
        }

        String s = e.getMessage()
                .replace("/", "")
                .replace("bukkit:", "")
                .replace("spigot:", "")
                .replace("minecraft:", "");

        if (s.equals("op") || s.startsWith("op ") || s.equals("deop") || s.startsWith("deop ")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Message.OP_DISABLED.toString());
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        if (e.getPlugin().getName().equalsIgnoreCase("Vault")) {
            plugin.tryVaultHook(true);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        plugin.refreshAutoOp(e.getPlayer());
    }
}
