package me.lucko.luckperms.commands.user.subcommands;

import me.lucko.luckperms.LuckPermsPlugin;
import me.lucko.luckperms.commands.Predicate;
import me.lucko.luckperms.commands.Sender;
import me.lucko.luckperms.commands.SubCommand;
import me.lucko.luckperms.constants.Message;
import me.lucko.luckperms.constants.Permission;
import me.lucko.luckperms.users.User;

import java.util.List;

public class UserGetUUID extends SubCommand<User> {
    public UserGetUUID() {
        super("getuuid", "Get the UUID of a user", "/%s user <user> getuuid", Permission.USER_GETUUID,
                Predicate.alwaysFalse());
    }

    @Override
    public void execute(LuckPermsPlugin plugin, Sender sender, User user, List<String> args, String label) {
        Message.USER_GETUUID.send(sender, user.getName(), user.getUuid().toString());
    }
}
