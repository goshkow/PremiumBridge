package goshkow.premlogin;

import org.bukkit.GameMode;
import org.bukkit.Location;

record PlayerRestoreState(
    Location location,
    float walkSpeed,
    float flySpeed,
    boolean allowFlight,
    boolean flying,
    GameMode gameMode
) {
}
