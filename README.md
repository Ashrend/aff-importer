![我的自定义图标](icon/aff_importer_icon_256.png)
# Arcaea资产管理工具

适用于Android平台的Arcaea资产管理工具。


## 功能

- ZIP 谱面包解析，自动提取元数据并追加到 `songlist`
- 对现有`songlist`元数据的预览与编辑
- 对现有`packlist`元数据的预览与编辑

## TODO LIST

- 乐曲批量编辑功能
- `unlocks` 相关资源的导入与管理
- 非加密 `arcpkg` 档案的有限支持

## 说明

- 本项目为全过程 Vibe Coding 产物，本人不对代码质量做任何保证。
- 本项目仅供学习交流，同时不包含也不提供任何Arcaea相关资源。
  
## 发布签名

Release 构建需要签名。`app/build.gradle.kts` 会读取 `keystore/keystore.properties`：

- 该文件存在 → release 自动签名
- 不存在 → release 仍可构建，只是产物未签名（无法安装）

`keystore/` 整个目录**不入库**（见 `.gitignore`），需要自己生成：

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

> 密钥库一旦丢失，**同一应用就无法再发布能覆盖安装的版本**（签名不同，Android 会拒绝升级安装，
> 只能先卸载再装，卸载会清掉应用数据，包括已保存的目标目录）。请把密钥库与口令单独备份到
> 安全的地方，不要只留在工作目录里。

## 许可证

GPL 3.0 - 详见 [LICENSE](./LICENSE)
