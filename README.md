
# WorldRelay 玩家数据加载

当前已实现共享世界的严格 UUID 玩家数据加载。世界根目录存在普通文件
`worldrelay.shared` 时启用；标记在玩家存储实例创建时读取，修改标记后需要重启世界。
普通单人世界不受影响。本次不会自动给已有存档添加标记。

- 房主和访客均只读取 `playerdata/<当前登录 UUID>.dat`，不使用 `level.dat` 的
  `Data.Player`，不回退到 `.dat_old`；保留原有保存、数据版本升级及 NeoForge 加载事件。
- 文件损坏、不可读或不是普通文件时拒绝加入，不覆盖文件。
- 主文件缺失时返回 `NEEDS_CREATION_CONFIRMATION`。确认 UI 尚未实现，默认提示并取消本次加入。
  本次不自动迁移旧存档，也不统一正版与离线 UUID。

后续 UI 通过 `SharedPlayerData` 接入：在 IO 工作线程上创建该世界的服务并调用
`check(uuid)`，仅在需要确认时调用 `requestCreation(uuid, confirmation)`。
`MissingPlayerDataConfirmation` 返回异步的 `CREATE`、`CANCEL` 或 `UNAVAILABLE`；
调用方等待异步结果后再重试加入，不得阻塞渲染线程或服务器线程。
远端接入时必须使用已认证玩家的 UUID，不能信任客户端指定任意 UUID。
`CREATE` 只授予当前进程内、绑定世界真实路径和 UUID 的一次初始化许可，
下一次实际读取时消费；如果文件已出现则加载该文件，如果文件损坏则报错。
许可允许原版初始化新玩家，不会直接写出空白玩家 NBT。

验证命令：`gradlew.bat test build`。测试覆盖共享与普通世界的实际登录检查、主文件读取、
缺失与损坏、创建确认和一次性许可；完整 A → B → A 联机交接、背包、位置、末影箱、
维度和模组附加数据仍需双客户端实机验证。

Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/
