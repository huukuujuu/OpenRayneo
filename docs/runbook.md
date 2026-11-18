# 运行指南

目标、模块和当前完成度统一见 [架构事实](../.arch/ground_truth.md) 与 [看板](../.arch/dashboard.html)。本页只维护可直接操作的安装、运行与恢复步骤。本分支仅含 Android；Mac Bridge 的构建与操作说明保留在 `main` 历史中。

## 当前本地候选

- Android：[37 release](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/OpenRayneo-v37-release.apk)、[37 debug](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/OpenRayneo-v37-debug.apk)，版本 `1.2.0-beta.4`。CI版本名为 `1.(run-23).0`，上传文件名 `openrayneo-<版本>-<sha7>.apk`。
- DSH：[插件0.1.8](../android/app/build/outputs/candidates/android37-1.2.0-beta.4/openrayneo-dsh-0.1.8.tgz)。Android36及之后沿用该版插件。
- Android回退：[36 release](../android/app/build/outputs/candidates/android36-1.2.0-beta.3/OpenRayneo-v36-release.apk)。同签名覆盖安装保留配置与记录，私有库与Keystore一起保留。

这些是开发机产物；当前源码构建以本页命令为准，仓库不提交安装包、模型下载物或私钥。

## 构建

Android需要JDK17、SDK Platform36 / Build-Tools36.0.0；使用 `ANDROID_HOME` 或不提交的 `android/local.properties` 指向本机SDK。

```sh
cd android
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug -Popenrayneo.abis=arm64-v8a,x86_64 --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.openrayneo.android/.MainActivity
```

默认Gradle输出在下一次构建生成；固定交付件放 `outputs/candidates/<版本>/`。原开发签名用于覆盖升级，保留 `~/.android/debug.keystore`，不在文档或源码写入密码。系统阻止ADB安装时，把APK推送到手机Download后由用户手动安装。

## 连接与恢复

1. 普通切换先在占用端断开眼镜，保留已有配对；手机、其他设备和官方App同时争用会阻塞数据通道。
2. Android授予附近设备权限后选择目标。首次配对用应用配对流程并由用户确认系统提示。
3. 分别检查BLE认证、RFCOMM数据通道及页面／音频业务状态。
4. `channel did not finish opening` 时保留诊断，清理本次失败连接；确认其他App释放连接、眼镜已展开佩戴并亮屏，再连接一次。不要连续点击连接、常规恢复不要删配对或清应用数据。
5. 眼镜没有唤醒响应时先区分设备无响应与手机没收到：查看应用日志中的语音事件及连接状态，再核助手／语音唤醒／唤醒词实际回报。助手页面尚未退出时先回到表盘。默认勾选或已发送指令不能代替设备读回。

蓝牙bond损坏时才重新配对；细节见 [协议](protocol.md)。

## Agent与记录同步

语音响应、等待／提示、工具和工作流由本机外部Agent负责；OpenRayneo负责音频、基础转写、记录与官方通路回写。

| 入口 | 默认监听 | 凭据 |
| --- | --- | --- |
| Android本机Agent API | loopback `8766`，设置中启用 | 手机本机token |
| 记录同步服务 | 手机LAN `8767`，独立启用 | 专用sync token |

`GET /health`无需token，其他路由使用Bearer；勿打印凭据。

手机在“设置 → DSH Go与外部Agent”启用本机入口；DSH选择“本机 / This phone”，先验证普通聊天。插件安装／工具见 [插件说明](../integrations/dsh-go/README.md)。tgz顶层为 `package/`，在手机DSH终端解压：

```sh
mkdir -p "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
tar -xzf /sdcard/Download/openrayneo-dsh-0.1.8.tgz --strip-components=1 -C "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
dsh plugin --profile web add "$HOME/.dsh/dsh-go/plugins/openrayneo-dsh"
```

首次安装执行add；更新使用原目录替换，再在DSH“引擎”页重启内核。连接动作存入`OPENRAYNEO_TOKEN`凭据服务。live原生唤醒跨wake复用对话，手机助手“新建对话”显式重开；录音／任务／wake身份各自独立。

记录同步服务的游标与派生写回规则见 [同步契约](record-sync-contract.md)；本分支不含其Mac客户端。

## 声音与录音

- 原生自动接听需要显式开启。双路默认ch1本人、ch2完整说话人；native／全天混合mono全量处理。手工登记默认选实时ch1。
- 手机“记录 → 记录日程”打开已结束记录，“导出WAV”保留原通道；当前正在录制的那条先停止并等待收尾。文件名不用于判断native／实时／全天来源。
- 本地ASR模型可放 `/sdcard/OpenRayneo/models/<官方归档目录名>/`（需“所有文件访问”权限），卸载应用后保留。
- 实际声纹仍有漏认与单人误分组，当前基准和下一步见 [未解决问题](../.arch/ground_truth.md#当前实现与未解决问题)。

## 数据与远端开发

手机加密记录与Keystore为用户数据，清构建产物时保留。原始APK／固件／反编译与当前诊断证据在已忽略的 `analysis/`，不作为发布资源。

部分开发证据在SSH别名 `ubuntu-rx7800xt` 的 `~/openrayneo-lab/`。远端工作使用 `ssh-control` 技能；保留原源码基线、模型、SDK和用户资料，临时排错代码阶段结束Trash，不将远端端口、密钥或个人记录抄进项目文档。
