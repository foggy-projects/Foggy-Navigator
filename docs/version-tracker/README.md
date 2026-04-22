# Version Tracker

按版本号跟踪需求、缺陷、重构和延期事项。

## 目录规则

- 每个版本一个目录，例如 `1.0.0-SNAPSHOT/`
- 版本目录下可同时存放需求、缺陷、设计补充、延期处理事项
- 文件命名使用 `NN-事项简述.md`

## 版本目录

- [1.0.0-SNAPSHOT](./1.0.0-SNAPSHOT/)
- [1.0.1-SNAPSHOT](./1.0.1-SNAPSHOT/)
- [1.0.2-SNAPSHOT](./1.0.2-SNAPSHOT/)
- [1.0.3-SNAPSHOT](./1.0.3-SNAPSHOT/)
- [1.1.0-SNAPSHOT](./1.1.0-SNAPSHOT/)
- [1.1.1-SNAPSHOT](./1.1.1-SNAPSHOT/)
- [1.2.0-SNAPSHOT](./1.2.0-SNAPSHOT/)
- [1.0.2-APP](./1.0.2-APP/)

## 说明

- `docs/version-tracker/<version>/` 是新增需求、缺陷、重构、延期事项的唯一默认登记入口
- `docs/requirement-tracker/` 保留为历史季度制归档目录，不再写入任何新事项
- 新增事项必须直接登记到对应版本目录，不再先写季度目录再迁移
