# Launcher 模块

应用启动器模块 - 负责打包和启动 Foggy Navigator 应用。

## 设计目的

- **唯一使用 spring-boot-maven-plugin 的模块**
- 所有业务模块（coding-agent, user-auth-module 等）都是普通库 JAR
- Launcher 模块负责将所有依赖打包成可执行的 Spring Boot JAR

## 构建

```bash
# 构建可执行 JAR
mvn clean package -pl launcher -am

# 输出位置
launcher/target/launcher-1.0.0-SNAPSHOT.jar
```

## 运行

```bash
# 使用默认配置运行
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar

# 指定 profile 运行
java -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker

# 开发环境使用 Maven 运行（可选）
cd launcher
mvn spring-boot:run -Dspring-boot.run.profiles=docker
```

## 架构优势

1. **清晰的职责分离**
   - 业务模块：纯业务逻辑，打包为普通 JAR
   - Launcher：负责启动和部署，打包为可执行 JAR

2. **灵活的依赖管理**
   - 在 launcher/pom.xml 中添加需要的业务模块
   - 支持多种部署组合

3. **便于测试和开发**
   - 业务模块可以被其他项目直接依赖
   - 业务模块 JAR 体积小，构建快

## 添加其他模块

在 `pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>com.foggy.navigator</groupId>
    <artifactId>tutor-agent</artifactId>
    <version>${project.version}</version>
</dependency>
```
