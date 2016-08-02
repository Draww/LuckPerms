package me.lucko.luckperms.commands.user.subcommands;

import me.lucko.luckperms.LuckPermsPlugin;
import me.lucko.luckperms.commands.Predicate;
import me.lucko.luckperms.commands.Sender;
import me.lucko.luckperms.commands.SubCommand;
import me.lucko.luckperms.constants.Message;
import me.lucko.luckperms.constants.Permission;
import me.lucko.luckperms.groups.Group;
import me.lucko.luckperms.users.User;

import java.util.List;

public class UserSetPrimaryGroup extends SubCommand<User> {
    public UserSetPrimaryGroup() {
        super("setprimarygroup", "Sets a users primary group",
                "/%s user <user> setprimarygroup <group>", Permission.USER_SETPRIMARYGROUP, Predicate.notOneOf(new Integer[]{1}));
    }

    @Override
    public void execute(LuckPermsPlugin plugin, Sender sender, User user, List<String> args, String label) {
        Group group = plugin.getGroupManager().getGroup(args.get(0).toLowerCase());
        if (group == null) {
            Message.GROUP_DOES_NOT_EXIST.send(sender);
            return;
        }

        if (user.getPrimaryGroup().equalsIgnoreCase(group.getName())) {
            Message.USER_PRIMARYGROUP_ERROR_ALREADYHAS.send(sender);
            return;
        }

        if (!user.isInGroup(group)) {
            Message.USER_PRIMARYGROUP_ERROR_NOTMEMBER.send(sender, label);
            return;
        }

        user.setPrimaryGroup(group.getName());
        Message.USER_PRIMARYGROUP_SUCCESS.send(sender, user.getName(), group.getName());

        saveUser(user, sender, plugin);
    }

    @Override
    public List<String> onTabComplete(Sender sender, List<String> args, LuckPermsPlugin plugin) {
        return getGroupTabComplete(args, plugin);
    }
}
