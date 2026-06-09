# Tabularis DM 插件

[Tabularis](https://github.com/TabularisDB/tabularis) 的 [DM / 达梦数据库](https://www.dameng.com/) 驱动插件。

插件是一个独立 Java 进程，通过 stdin/stdout 上的 JSON-RPC 2.0 与 Tabularis 通信。插件运行时从用户配置的本地路径加载官方达梦 JDBC 驱动。本仓库和发布产物都不会分发达梦 JDBC 二进制文件。

[English README](./README.md)

## 功能

- 通过用户本地提供的 JDBC jar 连接达梦
- 列出 schema、表、列，并返回表注释和列注释
- 在达梦字典暴露 identity 元数据时识别自增列；字典缺失时安全降级为 `false`
- 列出索引和外键，并补充兼容不同 Tabularis 版本的字段
- 列出视图、查看视图定义、加载视图列
- 列出函数/过程和 routine 参数
- 返回达梦 EXPLAIN 查询计划，支持 Tabularis Visual Explain
- 列出触发器、查看触发器定义
- 批量返回列和外键元数据，加快 Tabularis 浏览
- 返回 schema 快照，支持 Tabularis ER 图
- 在 SQL 编辑器执行查询、DML 和 DDL
- 通过插件侧 `execute_query_batch` JSON-RPC 执行多语句批处理
- 支持 Tabularis 行编辑 UI 的新增、更新、删除
- 生成表、列、索引、外键管理 SQL
- 创建、修改、删除视图
- 通过插件侧 JSON-RPC 创建和删除触发器
- 删除索引和外键
- 返回 Tabularis 兼容的结果集

routine 执行和 routine 管理暂不实现。trigger 浏览、查看 RPC 已实现，并通过 `capabilities.triggers` 暴露给 Tabularis；trigger 创建、删除 RPC 也已在插件侧实现，但当前 Tabularis external adapter 仍可能在触发器创建弹窗拦截，需要主项目后续补转发。`execute_query_batch` 也已在插件侧实现；当前 Tabularis external adapter 版本可能仍会把 SQL 编辑器批处理拆成多次 `execute_query`，直到主项目后续增加 adapter override。

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
target/tabularis-dameng-plugin-0.8.0.jar
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
cp target/tabularis-dameng-plugin-0.8.0.jar "$PLUGIN_DIR/target/"
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

## 本地演示 schema

本地验证时，Docker 达梦实例里的 `DEV2` schema 已准备了一组销售业务演示数据：

- 表：`CUSTOMERS`、`PRODUCTS`、`ORDERS`、`ORDER_ITEMS`、`ORDER_AUDIT`、`T_WRITE_TEST`
- 外键：订单关联客户、订单明细关联订单、订单明细关联产品
- 索引：客户/订单/产品查询索引，以及 `UX_PRODUCTS_SKU`
- 视图：`V_ORDER_SUMMARY`、`V_ORDER_DETAIL`、`V_CUSTOMER_LIFETIME_VALUE`、`V_PRODUCT_SALES`
- 函数/过程：`FN_CUSTOMER_ORDER_COUNT`、`FN_CUSTOMER_TOTAL_AMOUNT`、`P_REFRESH_ORDER_STATS`
- 触发器：`TRG_ORDERS_AUDIT`

这套数据已在 Tabularis 本地验证通过：schema、表、表注释、列、列注释、索引、外键、视图、视图列、视图查询、函数/过程、routine 参数、触发器、触发器定义、Visual Explain、ER 元数据、SQL 写入、行编辑以及视图/索引/外键管理路径都可以通过 `DM` 插件正常工作。触发器创建/删除 RPC 已在插件协议侧可用；当前 Tabularis external adapter 构建可能还需要主项目补路由后，触发器创建弹窗才能真正调用它们。

可复用初始化脚本在 `docs/demo-schema.sql`。

## 开发说明

- stdout 只输出 JSON-RPC 响应。
- 日志和诊断信息输出到 stderr。
- `initialize` 使用 `URLClassLoader` 加载 `dm.jdbc.driver.DmDriver`。
- `execute_query` 允许 SQL 编辑器写入和 DDL。有结果集的语句返回 rows；无结果集的语句返回 `affected_rows`。
- `execute_query_batch` 在同一个 JDBC 连接上按顺序执行多条语句，保持 autocommit，不自动包事务，单条失败后继续执行后续语句，并为每条语句返回 `{ result, error, execution_time_ms }`。该 RPC 可直接用 JSON-RPC 测试；当前 Tabularis external adapter 版本可能尚未转发。
- `get_databases` 返回可见 schema，方便 Tabularis 连接窗口中的“加载数据库”按钮有可选值。
- `get_tables` 从 `ALL_TAB_COMMENTS` 返回表注释。
- `get_columns`、`get_schema_snapshot`、`get_all_columns_batch` 从 `ALL_COL_COMMENTS` 返回列注释，并在达梦字典可用时返回 identity 自增信息。
- `get_indexes` 同时返回旧的逐列字段和 `index_name`、`columns` 等兼容字段。
- `get_foreign_keys` 同时返回 `ref_*` 和 `referenced_*` 字段名，兼容更多 Tabularis 版本。
- `get_schema_snapshot` 返回表、列和外键，供 Tabularis ER 图使用。
- `get_views`、`get_view_definition`、`get_view_columns` 查看视图；`create_view`、`alter_view`、`drop_view` 管理视图。
- `get_routines`、`get_routine_parameters` 只暴露函数/过程元数据。
- routine 定义优先读取 `ALL_SOURCE`；如果达梦未保存源码或权限不足，插件会返回生成的签名。
- `explain_query` 使用达梦 `EXPLAIN FOR`，把 JDBC 返回的计划行解析为 Tabularis `ExplainPlan` 树，并保留原始计划行；旧的文本 EXPLAIN 解析保留为兜底。
- `get_triggers`、`get_trigger_definition` 已实现，并通过 `capabilities.triggers: true` 暴露；插件侧 `create_trigger`、`drop_trigger` 已为直接 JSON-RPC 和后续主项目转发准备好。

## 发布包内容

发布产物命名示例：

```text
tabularis-dameng-plugin-0.8.0.zip
```

zip 应包含：

```text
dameng-plugin
dameng-plugin.bat
manifest.json
target/tabularis-dameng-plugin-0.8.0.jar
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
