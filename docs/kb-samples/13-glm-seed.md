# GLM 种子域总览（样例）

> 本文为**样例文档**，仅用于知识库功能验证，不含真实业务数据。

## 1 Overview / 概述

GLM seed domains are the five core configuration domains shipped with the platform: REGION_TARIFF, PROJECT_ENV, ALARM_THRESHOLD, PROJECT_MEMBER and CURRENCY. They are called "seed" domains because they are initialized from seed data and then maintained through the standard definition/data/publish flow.

GLM 种子域指平台随包初始化的五个核心配置域：区域费率、项目环境、告警阈值、项目成员与币种。它们由种子数据初始化，之后按"定义 → 数据 → 发布"的标准流程维护。

## 2 Domain list / 域清单

| Domain | 中文名 | Scope | Owner |
| --- | --- | --- | --- |
| REGION_TARIFF | 区域费率 | REGION | 财务结算 |
| PROJECT_ENV | 项目环境 | PROJECT | 项目实施 |
| ALARM_THRESHOLD | 告警阈值 | REGION | 运维 |
| PROJECT_MEMBER | 项目成员 | PROJECT | 项目经理 |
| CURRENCY | 币种 | GLOBAL | 财务结算 |

## 3 Initialization / 初始化

1. Definitions are created first / 先建定义；
2. Seed data is loaded in dependency order: CURRENCY → REGION → PROJECT_ENV → PROJECT_MEMBER → REGION_TARIFF → ALARM_THRESHOLD / 按依赖顺序灌种子数据；
3. Seeding is idempotent: existing rows with the same key are skipped, not overwritten / 种子灌入幂等，同主键已存在则跳过。

## 4 Common pitfalls / 常见坑

- Do not edit seed rows in place during initialization / 初始化期间不要直接改种子行；
- Currency must exist before any tariff row / 费率前必须先有币种；
- Project environment must exist before members reference it / 成员引用环境前环境必须存在；
- Alarm thresholds depend on metric definitions, not on projects / 告警阈值依赖指标定义，不依赖项目。

## 5 FAQ / 常见问题

**Q: Can I add a new seed domain? / 可以新增种子域吗？**
A: Yes, but it must follow the same five-step contract: definition, seed, import, precheck, publish. / 可以，但必须走同样的五步：定义、种子、导入、预检查、发布。

**Q: Is GLM an acronym for a model? / GLM 是模型缩写吗？**
A: In this repository, GLM refers to the seed-domain package name, not to a language model. / 在本仓库里，GLM 指种子域包名，不是语言模型。

> 本文为样例文档，仅用于知识库功能验证，不含真实业务数据。
