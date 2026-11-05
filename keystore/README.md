# 发布签名目录

本目录存放**发布签名**用的密钥库与口令配置。**两者都不入库**（见仓库根目录 `.gitignore`）。

| 文件 | 是否入库 | 说明 |
|------|----------|------|
| `aff-importer-release.jks` | ❌ 否 | 发布用密钥库（PKCS12 / RSA 2048） |
| `keystore.properties` | ❌ 否 | 密钥库路径与口令（**明文**） |
| `README.md` | ✅ 是 | 本说明 |

`app/build.gradle.kts` 会读取 `keystore/keystore.properties`；该文件存在时 release 构建自动签名，
不存在时 release 仍然可以构建，只是产物未签名（无法安装）。

## 新环境如何生成

```powershell
keytool -genkeypair -v `
  -keystore keystore/aff-importer-release.jks -storetype PKCS12 `
  -alias aff-importer -keyalg RSA -keysize 2048 -validity 10950 `
  -storepass <口令> `
  -dname "CN=ArcaeaImporter, OU=App, O=Ashrend, L=Shenzhen, ST=Guangdong, C=CN"
```

然后新建 `keystore/keystore.properties`：

```properties
storeFile=keystore/aff-importer-release.jks
storePassword=<口令>
keyAlias=aff-importer
keyPassword=<口令>
```

> **PKCS12 不支持独立的 key 口令**：私钥实际由 store 口令保护，所以 `keyPassword` 必须与
> `storePassword` 完全一致，否则构建会报
> `KeytoolException: Given final block not properly padded`。

## 注意

密钥库一旦丢失，**同一应用就无法再发布能覆盖安装的版本**（签名不同，Android 会拒绝升级安装，
只能先卸载再装，卸载会清掉应用数据，包括已保存的目标目录）。请把这两个文件单独备份到
安全的地方，不要只留在工作目录里。
