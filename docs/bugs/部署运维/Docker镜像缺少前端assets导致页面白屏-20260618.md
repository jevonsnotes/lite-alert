---
severity: P1
status: fixed
first_seen: 2026-06-18
occurrences:
  - date: 2026-06-18
    description: Docker 部署后页面无法正常显示，浏览器请求 /assets/index-*.js 返回 404，CSS 请求返回 application/json MIME 类型错误。
---

# Docker镜像缺少前端assets导致页面白屏

## 根因分析

Docker 自动化构建执行 `mvn -pl backend -am package -DskipTests` 时，Maven 生命周期中 `process-resources` 早于 `prepare-package`。

当前前端构建由 `backend/pom.xml` 中的 `frontend-maven-plugin:npm` 执行，绑定在 `prepare-package` 阶段，运行 `npm run build`。而 `frontend/vite.config.mjs` 将 Vite 产物输出到 `../backend/src/main/resources/static`。

在 GitHub Actions / Docker Build 的干净 checkout 环境中，`.gitignore` 忽略了 `backend/src/main/resources/static/assets/`，因此 `process-resources` 阶段复制到 `backend/target/classes/static` 的内容只有仓库中已有的 `index.html` 和 `favicon.svg`。随后 `prepare-package` 阶段才生成 `assets/` 到源码资源目录，但 Maven 不会自动重新执行资源复制，最终 Spring Boot JAR 中缺少 `BOOT-INF/classes/static/assets/`。

涉及文件与方法/阶段：

- `frontend/vite.config.mjs`：`build.outDir` 当前输出到源码资源目录。
- `backend/pom.xml`：`frontend-maven-plugin` 的 `npm-build` execution 绑定 `prepare-package` 阶段。
- `docker/Dockerfile`：构建阶段执行 `mvn -B -q -pl backend -am package -DskipTests`。
- `.github/workflows/docker-publish.yml`：GitHub Actions 使用 `docker/build-push-action` 构建并推送镜像。
- `backend/src/main/java/io/litealert/common/config/WebMvcConfig.java:addResourceHandlers()`：生产环境从 `classpath:/static/assets/` 提供 `/assets/**` 静态资源。
- `backend/src/main/java/io/litealert/common/error/GlobalExceptionHandler.java:handleNoResource()`：静态资源不存在时返回 JSON 404，导致 CSS 请求额外表现为 MIME type `application/json`。

数据流转链路：浏览器请求 `/` → Spring Boot 返回 `BOOT-INF/classes/static/index.html` → `index.html` 引用 `/assets/index-*.js` 和 `/assets/index-*.css` → JAR 中没有对应 `BOOT-INF/classes/static/assets/` 文件 → Spring 静态资源查找失败 → `NoResourceFoundException` 被统一异常处理为 JSON 404 → 页面白屏，CSS 被浏览器拒绝应用。

## 解决方案

调整前端构建产物进入 JAR 的路径，不再依赖 `process-resources` 之后写回源码目录。

1. 修改 `frontend/vite.config.mjs`：
   - 将 `build.outDir` 从 `../backend/src/main/resources/static` 改为 `../backend/target/frontend-static`。
   - 保留 `emptyOutDir: true`，只清理 Maven target 下的临时前端产物目录。

2. 修改 `backend/pom.xml`：
   - 保留 `frontend-maven-plugin` 在 `prepare-package` 阶段执行 `npm run build`。
   - 新增 `maven-resources-plugin` 的 `copy-frontend-static` execution，同样绑定 `prepare-package`，放在 `frontend-maven-plugin` 之后声明。
   - 将 `${project.build.directory}/frontend-static` 复制到 `${project.build.outputDirectory}/static`，覆盖旧 `index.html` 并补充 `assets/`。

3. 验证：
   - 修复前，在干净 checkout 中执行 `mvn -B -q -pl backend -am clean package -DskipTests`，JAR 中缺少 `BOOT-INF/classes/static/assets/*.js`，RED 已复现。
   - 修复后执行同一打包命令，检查 `jar tf backend/target/lite-alert.jar | grep 'BOOT-INF/classes/static/assets'` 能看到 JS/CSS 文件。

## 处理记录

| 日期 | 出现模块/场景 | 尝试方案 | 结果 | 说明 |
|------|-------------|----------|------|------|
| 2026-06-18 | Docker 镜像自动化构建 | 干净 checkout 执行 Maven package 并检查 JAR 内容 | 复现 | JAR 中缺少 `BOOT-INF/classes/static/assets/`，与线上容器现象一致 |
| 2026-06-18 | Maven 前端打包流程 | Vite 输出到 `target/frontend-static`，再由 `maven-resources-plugin` 复制到 `target/classes/static` | 有效 | 本地 package 和干净 checkout 模拟 CI 验证均确认 JAR 中包含 `BOOT-INF/classes/static/assets/*.js` |
