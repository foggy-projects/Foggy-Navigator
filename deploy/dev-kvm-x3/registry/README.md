# Harbor Registry Notes

Navigator 镜像统一推送到：

```text
test.synthoflow.com:8080/x3
```

组件命名：

```text
navigator-backend
navigator-frontend
```

完整镜像：

```text
test.synthoflow.com:8080/x3/navigator-backend:<image-tag>
test.synthoflow.com:8080/x3/navigator-frontend:<image-tag>
```

Worker 不使用 Harbor 镜像命名，继续通过 OBS 安装包分发：

```text
https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker
https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker
https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/gemini-worker
```

Harbor 当前为内网 HTTP，所有 build/pull 节点需要在 Docker daemon 配置：

```json
{
  "insecure-registries": ["test.synthoflow.com:8080"]
}
```

`remote/init-host.sh` 会自动合并该配置并重启 Docker。
