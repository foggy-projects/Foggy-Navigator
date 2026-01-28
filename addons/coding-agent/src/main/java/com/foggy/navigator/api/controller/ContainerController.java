package com.foggy.navigator.api.controller;

import com.foggy.navigator.api.model.ContainerInfo;
import com.foggy.navigator.api.service.ContainerService;
import com.foggy.navigator.common.annotation.RequireAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 容器管理 Controller
 */
@RestController
@RequestMapping("/api/v1/containers")
@RequireAuth
@Slf4j
public class ContainerController {

    @Autowired
    private ContainerService containerService;

    /**
     * 获取容器列表
     * GET /api/v1/containers?status=RUNNING
     */
    @GetMapping
    public ResponseEntity<List<ContainerInfo>> listContainers(
            @RequestParam(required = false) String status) {
        log.info("GET /api/v1/containers - 查询容器列表: status={}", status);

        List<ContainerInfo> containers = containerService.listContainers(status);
        return ResponseEntity.ok(containers);
    }

    /**
     * 获取单个容器信息
     * GET /api/v1/containers/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ContainerInfo> getContainer(@PathVariable String id) {
        log.info("GET /api/v1/containers/{} - 查询容器", id);

        ContainerInfo container = containerService.getContainer(id);
        if (container == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(container);
    }

    /**
     * 删除容器
     * DELETE /api/v1/containers/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteContainer(@PathVariable String id) {
        log.info("DELETE /api/v1/containers/{} - 删除容器", id);

        containerService.deleteContainer(id);
        return ResponseEntity.noContent().build();
    }
}
