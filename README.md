# FlyCredit

Reach the roof of your next build, work on a friend’s tower, or give another player a few minutes in the air. FlyCredit brings timed flight to GriefDefender claims on Paper and Purpur. Your balance lasts across sessions, and you spend it only while you’re actually flying.

## Fly where you’re trusted

Use `/claimfly` to take off in Survival or Adventure. You can fly in your own claims and in another player’s claim when they’ve granted you either **trust** (`BUILDER`) or **permissiontrust** (`MANAGER`). Subclaims follow GriefDefender’s inheritance settings.

Touch down to pause the timer. Use `/claimfly` again to end the session and return the unused time to your balance. Leaving an eligible claim, losing trust, or running out of session time ends the flight automatically.

If flight ends in midair, you have a short window to land safely. By default, the protection lasts up to **15 seconds** and covers **one landing**. Logging out or restarting the server preserves only the remaining time.

## Give another player flight time

Use `/flycredit give <player> <minutes>` to send flight time from your balance to an eligible online player. Both players receive a chat confirmation showing how much time changed hands.

Server owners choose which **LuckPerms groups** can receive credit, including ranks awarded through **PlayTimeManager**. On a fresh installation, the recipient list is empty. To enable transfers, add your server’s LuckPerms groups to `transfer.allowed-recipient-groups` in `plugins/FlyCredit/config.yml` and restart the server. By default, players must be direct members of a listed group to receive credit. The `flycredit.pay` permission controls who can give credit.

## Player commands

| Command | What it does |
|---|---|
| `/claimfly` | Start or end a flight session. |
| `/flycredit <minutes>` | Reserve the number of minutes you choose from your balance and start a flight session. |
| `/flycredit info` | Check your balance, flight usage, transfers, and available PlayTimeManager playtime. |
| `/flycredit give <player> <minutes>`  | Give some of your available credit to an eligible online player. |

## Server requirements

FlyCredit requires [**GriefDefender**](https://www.spigotmc.org/resources/1-12-2-26-3-griefdefender-claim-plugin-grief-prevention-protection.68900/), [**LuckPerms**](https://modrinth.com/plugin/luckperms), and [**PlayTimeManager**](https://modrinth.com/plugin/playtimemanager).

Administrators can grant or adjust credit in minutes. Balances and transfers are stored locally in **SQLite**.

<p align="center">
  <a href="https://discord.gg/Zn5d9d2pBU" target="_blank">
    <img src="https://i.ibb.co/8Dh71sx0/Discord-logo.png" alt="Discord" width="300">
  </a>
</p>
<p align="center">
  <em>Join the Discord if you have any questions or need support!</em>
</p>
