# release/ —— 正式版留档

本目录放 TalkTact 的**正式版**安装包。主仓库留一份，官网下载按钮指向主仓库的 release 链接。

## 只保留最新一版

与测试包（`test-0.8.x`，同样只留最新）的规矩一致：发新正式版时把这里的旧包删掉。
历史版本仍在 git 提交记录与模块仓库的 release 里。

## 当前留档

| 项 | 值 |
| :--- | :--- |
| 版本 | **0.8.10**（versionCode 37） |
| 文件 | `TalkTact-0.8.10.apk`（74,540,006 字节 ≈ 71 MiB） |
| sha256 | `7163d4737f8dcca625f3c0a43becaf73e3e75fab24946dd792304f3431d60f11` |
| 来源 | 模块仓库 `Xposed-Modules-Repo/io.github.shibry88_netizen.talktact` 的 release `37-0.8.10` 同名附件 |
| 释出日期 | 2026-10-06 |
| 主仓库 release | tag `v0.8.10`（与同版本正式源码 tag 一致） |

## 发布流程

`publish.yml` **不动** —— 正式版照旧由它发到模块仓库。主仓库这一份是**手工补的**，每发一次正式版做两步：

1. 建/更新主仓库 release。tag 用**已经存在**的那个 `v0.8.x`，并加 `--verify-tag`
   （防止 tag 不存在时被自动创建并 push —— 那会触发 publish.yml 重发一次模块库）：

   ```bash
   gh release download <模块库tag> -R Xposed-Modules-Repo/io.github.shibry88_netizen.talktact -D /tmp/dl
   gh release create v0.8.x --verify-tag --title "0.8.x 正式版" --notes-file /tmp/notes.txt \
       /tmp/dl/TalkTact-0.8.x.apk /tmp/dl/TalkTact-0.8.x.apk.sha256
   ```

2. 更新本目录：删旧包、放新包 + `.sha256`、改上面那张表，提交。

   ```bash
   git rm release/TalkTact-0.8.<旧>.apk release/TalkTact-0.8.<旧>.apk.sha256
   cp /tmp/dl/TalkTact-0.8.x.apk* release/
   ```

## 为什么 APK 能进仓库

`.gitignore` 里有 `*.apk`（防止构建产物混入），本目录是**唯一例外**（`!release/*.apk`）。
一个 APK 约 71MB，进了 git 历史就不会消失 —— 所以这里**只留最新一版**，别堆历史包。

## 官网下载链接

```
https://github.com/shibry88-netizen/TalkTact/releases/download/v0.8.10/TalkTact-0.8.10.apk
```
