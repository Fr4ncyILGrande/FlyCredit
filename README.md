![FlyCredit](https://cdn.modrinth.com/data/TjurmNm3/images/03c32fbde7c369fd902946b6d8b18c985ed74f4d.png)

# FlyCredit

Reach the roof of your next build, work on a friend’s tower, or give another player a few minutes in the air. FlyCredit brings timed flight to GriefDefender claims on Paper and Purpur. Your balance carries over between sessions, and you spend credit only while you’re actually flying.

## Fly where you’re trusted

Use `/flycredit <minutes>` to choose how much time to reserve and take off in **Survival** or **Adventure**. You can fly in your own claims and in another player’s claim when they’ve personally granted you **trust** (`BUILDER`) or **permissiontrust** (`MANAGER`). Subclaims follow GriefDefender’s inheritance settings.

You can fly straight from your own build to a trusted neighbor’s roof in the same session. FlyCredit checks your destination when you cross into another block or teleport. If you enter an area where you can’t fly or the owner revokes your trust, the session ends and your unused time returns to your balance.

**Hovering uses credit. Touching down pauses the timer.** The plugin checks the surface beneath your feet, so landing on a slab or stair counts too. The flight bar shows how much of your reserved time remains and whether the timer is paused. It turns yellow at two minutes and red for the final thirty seconds.

Use `/flycredit off` to finish early and reclaim the time you have left.&#x20;

If flight ends in midair, you have a short window to land safely. By default, the protection lasts up to **15 seconds** and covers **one landing**. Even a harmless landing uses it up. If you log out during the descent or the server shuts down normally, only the remaining protection time carries over when you return.

## Give another player flight time

Use `/flycredit give <player> <minutes>` to send flight time to an eligible online player you can see. You can give away any credit outside your current reservation, even while flying. FlyCredit records the debit and the credit together, and both players receive a chat confirmation showing the exact time transferred.

Server owners choose which **LuckPerms groups** can receive credit, including ranks awarded through **PlayTimeManager**. On a fresh installation, the recipient list is empty. To enable gifts, add your groups to `transfer.allowed-recipient-groups` in `plugins/FlyCredit/config.yml` and restart the server.

By default, `DIRECT` requires the recipient to be a direct member of a listed group in their current context. Choose `INHERITED` to accept inherited groups as well. The `flycredit.pay` permission controls who can **give** credit.

## Player commands

| Command                                             | What it does                                           |
| --------------------------------------------------- | ------------------------------------------------------ |
| `/flycredit <minutes>`                              | Reserve the time you choose and start flying.          |
| `/flycredit off`                                    | End your flight and reclaim unused time.               |
| `/flycredit info`                                   | Check your balance, flight usage, gifts, and playtime. |
| `/flycredit give \<player> \<minutes>`              | Give flight time to an eligible online player.         |

**Flights and gifts both accept minutes and seconds.** Enter a whole number for minutes. To include seconds, add a period or comma followed by two digits from `00` to `59`. Both `1.54` and `1,54` mean **1 minute and 54 seconds**.

Commands are always in English. Chat messages and the flight bar follow each player’s Minecraft language, with **Italian for Italian clients** and **English for everyone else**. With `/flycredit info`, players can also see their PlayTimeManager playtime. If a live reading is unavailable, FlyCredit shows the most recent saved reading and its date, when available.

On first startup, FlyCredit creates **`plugins/FlyCredit/flycredit.db`**. Balances are tied to player UUIDs, so players keep their credit when they change their Minecraft name.&#x20;

## Administrator commands

These commands require **`flycredit.admin`**, granted to operators by default.

| Command | What it does |
| :---: | :---: |
| `/flycredit add <player> <minutes>` | Add flight time to a player’s available balance. |
| `/flycredit info <player>` | View another player’s balance, flight usage, gifts, and playtime. |
| `/flycredit diagnostics` | Check server details, dependencies, and transfer settings. |

If your server awards flight time as a weekly playtime reward, configure your reward system to run `flycreditadd <player> <minutes>` from the console whenever the reward is due.

You can use a player’s UUID in place of their name.

## Server requirements

FlyCredit requires [**GriefDefender**](https://www.spigotmc.org/resources/1-12-2-26-3-griefdefender-claim-plugin-grief-prevention-protection.68900/), [**LuckPerms**](https://modrinth.com/plugin/luckperms), and [**PlayTimeManager**](https://modrinth.com/plugin/playtimemanager).

FlyCredit stores balances and transfers locally in **SQLite**, using the driver supplied by the server. 
A normal shutdown returns unused session time to each player. After an unexpected shutdown, FlyCredit recovers the last saved remainder and credits it back once.

<p align="center">
  <a href="https://discord.gg/Zn5d9d2pBU" target="_blank">
    <img src="https://i.ibb.co/8Dh71sx0/Discord-logo.png" alt="Discord" width="300">
  </a>
</p>
<p align="center">
  <em>Join the Discord if you have any questions or need support!</em>
</p>
