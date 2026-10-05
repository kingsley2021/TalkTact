# signing/

**发布签名密钥的加密备份。** 放这里是为了「哪怕 CI Secrets 丢了，也还能把钥匙找回来重新出包」。

## 这里面是什么

- `key.p12.enc` —— `keystore/talktact.p12`（发布签名用的 PKCS#12）经 **AES-256-CBC + PBKDF2(600000 次迭代) + salt** 加密后的密文。

## 为什么可以放公开仓库

密文本身不含任何明文信息，安全性完全取决于口令强度：口令是 32 位随机串，不在这个仓库里。
**没有口令的密文 ≈ 一串无意义的字节**；反过来，口令一旦泄露就等于密钥泄露（`key.p12.enc` 是公开的）。

## 怎么用

CI 的还原顺序（见 `.github/workflows/build.yml` 与 `publish.yml`）：

1. 优先用 `KEYSTORE_BASE64`（Secrets 里的原始密钥）；
2. **它为空时**，自动回退到这里：解出 `signing/key.p12.enc` → `keystore/talktact.p12`，口令取自 Secrets `KEYSTORE_PASSPHRASE`。

本地手动解出来：

```bash
mkdir -p keystore
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \
  -in signing/key.p12.enc -out keystore/talktact.p12 \
  -pass pass:'<口令>'
```

（`keystore/` 已在 `.gitignore` 里，解出来的明文不会被误提交。）

## 口令在哪

- CI：仓库 Secrets → `KEYSTORE_PASSPHRASE`
- 维护者/协作方：见项目交接文档里的「密钥备份方案」一节

## 相关约束

- 证书指纹（SHA-256，无分隔大写）：`F8D1C2B43AE5EBD3169493937A7DF7AD2C23C59197E29E999E7FCBA626807AD5`
  CI 每次构建都会断言 APK 的签名证书就是这个值。
- **换密钥 = 用户必须卸载重装**（Android 不允许覆盖安装不同签名的包）。所以：非必要不换；
  真要换，先在 `publish/{SUMMARY,README.md}` 和 release 说明里写清楚，再走一次上面的流程并同步更新本文件与文档。
