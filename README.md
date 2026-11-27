# GGNext

GGNext 是一款面向 Android 平台的开源内存分析与调试工具，基于 Kotlin、Jetpack Compose 与 C++20 原生引擎构建。

目前本项目为100% vibecoding 制作，项目较混乱，不建议进行维护

项目源于在 Android 平台上对独立内存扫描引擎与现代化调试界面的实践重构，用于在本地开发测试、故障排查与安全研究场景中查看、检索、调试和修改目标进程的运行期内存。

## 主要功能

- **双工作模式**：支持 Root 直读与免 Root 共享 UID 两种工作模式
- **内存搜索**：支持精确数值、区间范围、模糊变化、联合搜索、指针搜索、字节掩码与异或编码搜索
- **内存浏览与编辑**：支持多类型实时浏览、地址跳转、偏移计算、内存复制与导出、目标进程内存页分配（支持自定义读/写/执行权限）、数值锁定与批量修改
- **硬件断点调试器**：支持 Perf 与 Ptrace 双后端（可在设置页切换），提供只读 / 只写 / 读写 / 执行硬件断点、指令实际读写类型识别、命中次数统计、通用/浮点寄存器实时编辑写回与调用链回溯

## 构建环境

需要安装 JDK 17、Android SDK 与 Android NDK，在项目根目录执行：

```bash
./gradlew assembleDebug
```

构建产物默认输出至 `app/build/outputs/apk/debug/app-debug.apk`。

## 鸣谢与参考来源

- [GameGuardian](https://gameguardian.net)：Android 平台经典的内存分析与修改工具先驱
- [AGameGuardian](https://github.com/AGameGuardian/agameguardian.github.io)：感谢其优秀的界面设计，本项目从中获取了灵感。
- [ShadowHook](https://github.com/bytedance/android-shadowhook)：优秀的开源 Android inline hook 框架。
- [xDL](https://github.com/hexhacking/xDL)：Android DL 动态库与符号解析库。
- [Keystone](https://www.keystone-engine.org/) / [Capstone](https://www.capstone-engine.org/)：汇编与反汇编引擎支持

## 开源协议与声明

本工具仅限用于合法授权的本地调试与技术研究，严禁用于任何违法或侵犯他人权益的用途，一切使用责任由使用者自行承担。

本项目严格遵循 [GPL-v3.0](LICENSE) 协议开源。