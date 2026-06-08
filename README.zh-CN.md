# Tabularis DM 插件

[Tabularis](https://github.com/TabularisDB/tabularis) 的只读 [DM / 达梦数据库](https://www.dameng.com/) 驱动插件。

插件是一个独立 Java 进程，通过 stdin/stdout 上的 JSON-RPC 2.0 与 Tabularis 通信。插件运行时从用户配置的本地路径加载官方达梦 JDBC 驱动。本仓库和发布产物都不会分发达梦 JDBC 二进制文件。

[English README](./README.md)

## 功能

- 通过用户本地提供的 JDBC jar 连接达梦
- 列出 schema、表、列
- 列出索引和外键
- 列出视图、查看视图定义、加载视图列
- 批量返回列和外键元数据，加快 Tabularis 浏览
- 返回 schema 快照，支持 Tabularis ER 图
- 执行只读 SQL 查询
- 返回 Tabularis 兼容的结果集
- 拒绝写入、CRUD 和 DDL 操作

插件仍然保持只读。存储过程、触发器、写操作、DDL、表结构管理、视图管理和 UI 扩展暂不实现。

## 环境要求

- Java 17 或更高版本
- Maven 3.9+，用于源码构建
- 支持外部插件的 Tabularis
- 本地达梦 JDBC 驱动 jar

达梦数据库和 JDBC 驱动请从达梦官方下载页获取：

https://www.dameng.com/download/index.html

本地开发建议优先使用 `DmJdbcDriver8.jar`。如果 JDK 21 下遇到兼容问题，可以尝试 `DmJdbcDriver11.jar`。

## 构建

```bash
mvn clean package
chmod +x dameng-plugin
```

构建产物位置：

```text
target/tabularis-dameng-plugin-0.2.0.jar
```

## 本地安装

Tabularis 当前用户插件目录：

```text
~/Library/Application Support/com.debba.tabularis/plugins/
```

macOS 安装方式：

```bash
PLUGIN_DIR="$HOME/Library/Application Support/com.debba.tabularis/plugins/dameng"

mkdir -p "$PLUGIN_DIR/target"
cp manifest.json dameng-plugin dameng-plugin.bat "$PLUGIN_DIR/"
cp target/tabularis-dameng-plugin-0.2.0.jar "$PLUGIN_DIR/target/"
chmod +x "$PLUGIN_DIR/dameng-plugin"
```

把 JDBC jar 放到稳定位置，例如：

```bash
mkdir -p "$HOME/Library/Application Support/com.debba.tabularis/jdbc"
cp /path/to/DmJdbcDriver8.jar \
  "$HOME/Library/Application Support/com.debba.tabularis/jdbc/"
```

安装或覆盖插件后需要重启 Tabularis。

## 在 Tabularis 中配置

打开 `Settings -> Plugins -> DM`，设置：

```text
jdbc_driver_path=/Users/<you>/Library/Application Support/com.debba.tabularis/jdbc/DmJdbcDriver8.jar
```

新建达梦连接：

```text
host=127.0.0.1
port=5236
database=留空；如果 Tabularis 要求选择，可选择加载出来的 schema
username=SYSDBA
password=<你的密码>
```

达梦 JDBC URL 不需要数据库名。插件使用：

```text
jdbc:dm://host:port
```

schema 由 Tabularis 单独选择和传递。

## 开发说明

- stdout 只输出 JSON-RPC 响应。
- 日志和诊断信息输出到 stderr。
- `initialize` 使用 `URLClassLoader` 加载 `dm.jdbc.driver.DmDriver`。
- `execute_query` 只允许 `SELECT`、`WITH`、`EXPLAIN` 开头的 SQL。
- `get_databases` 返回可见 schema，方便 Tabularis 连接窗口中的“加载数据库”按钮有可选值。
- `get_schema_snapshot` 返回表、列和外键，供 Tabularis ER 图使用。
- `get_views`、`get_view_definition`、`get_view_columns` 只做只读查看。

## 发布包内容

发布产物命名示例：

```text
tabularis-dameng-plugin-0.2.0.zip
```

zip 应包含：

```text
dameng-plugin
dameng-plugin.bat
manifest.json
target/tabularis-dameng-plugin-0.2.0.jar
```

不要包含：

```text
DmJdbcDriver*.jar
jdbc-*.zip
```

## 排查问题

如果 Tabularis 中看不到 DM 插件，请复制插件目录后重启 Tabularis。

如果 `SYSDBA` 能连接，但其他用户不能连接，请确认该用户存在于当前达梦实例：

```sql
SELECT USERNAME, ACCOUNT_STATUS FROM DBA_USERS ORDER BY USERNAME;
```

如果“加载数据库”没有结果，可以直接运行插件脚本并手动输入 JSON-RPC 行：

```bash
"$HOME/Library/Application Support/com.debba.tabularis/plugins/dameng/dameng-plugin"
```

## 许可证

本适配器项目使用 Apache-2.0 许可证。达梦 JDBC 驱动二进制文件归达梦所有，本仓库不分发。
