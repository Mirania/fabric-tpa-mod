# TPA and homes for Fabric (1.20.1)
A server-side Fabric/Quilt mod that adds /tpa command-set.  
Works for Minecraft 1.20.1 (snapshots not fully tested)  
Requires [FabricAPI](https://www.curseforge.com/minecraft/mc-mods/fabric-api)  

## Commands
All commands except `/warp set` and `/warp delete` don't require OP permissions. They're meant for everyone on a server to use.

`/tpa <player>` - Initiates request for you to teleport to `<player>`  
`/tpaaccept [<player>]` - Accept a tpa or tpahere request you've received, argument required if multiple ongoing  

`/home set <name>` - Sets a permanent warp point at your current location. Only you can see and use this warp.
`/home delete <name>` - Deletes one of your warp points
`/home list` - Lists your warp points. You can click them to instantly warp there
`/home tp <name>` - Warps to one of your warp points

`/warp set <name>` - Sets a permanent warp point at your current location. This warp is global, meaning everyone can see and use it. (Requires OP permission level 4)
`/warp delete <name>` - Deletes one of the global warp points. (Requires OP permission level 4)
`/warp list` - Lists all global warp points. You can click them to instantly warp there
`/warp tp <name>` - Warps to one of the global warp points

## Configuration
Configuration is saved in `config/FabricTPA.properties`, from which the values are loaded at server startup.
It also updates when a setting is changed in-game.

`timeout` - How long should it take for a tpa or tpahere request to time out, if not accepted/denied/cancelled. Default: 60 (seconds)  
`stand-still` - How long should the player stand still for after accepting a tpa or tpahere request. Default: 5 (seconds)  
`disable-bossbar` - Whether to disable the boss bar indication for standing still, if set to true will use action bar for time. Default: false  
`cooldown` - The minimum time between teleporting and the next request. Default: 5 (seconds)  
`cooldown-mode` - The mode for the cooldown, one of 3 values: `WhoTeleported`, `WhoInitiated`, `BothUsers`. Default: `WhoTeleported`. More info below 
`homes` - The amount of homes each player is allowed to have. Default: 6 

## Cooldown modes

`WhoTeleported` - The cooldown is applied to whoever got teleported  
`WhoInitiated` - Cooldown is applied to whoever initiated the request  
`BothUsers` - The Cooldown is applied to both players involved in the teleport request
