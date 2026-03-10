package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.SharingKeyDTO;
import com.foggy.navigator.common.form.SharingKeyCreateForm;
import com.foggy.navigator.common.form.SharingKeyUpdateForm;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 共享密钥管理端点 — Agent Owner 使用，需要登录认证
 */
@RestController
@RequestMapping("/api/v1/sharing-keys")
@RequireAuth
@RequiredArgsConstructor
public class SharingKeyController {

    private final SharingKeyService sharingKeyService;

    /** 创建共享密钥（明文 key 仅此一次返回） */
    @PostMapping
    public RX<SharingKeyDTO> create(@RequestBody SharingKeyCreateForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(sharingKeyService.create(userId, form));
    }

    /** 列出当前用户的所有共享密钥 */
    @GetMapping
    public RX<List<SharingKeyDTO>> list() {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(sharingKeyService.listByOwner(userId));
    }

    /** 更新共享密钥配置 */
    @PutMapping("/{id}")
    public RX<SharingKeyDTO> update(@PathVariable String id, @RequestBody SharingKeyUpdateForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(sharingKeyService.update(id, userId, form));
    }

    /** 删除共享密钥 */
    @DeleteMapping("/{id}")
    public RX<Void> delete(@PathVariable String id) {
        String userId = UserContext.getCurrentUserId();
        sharingKeyService.delete(id, userId);
        return RX.ok();
    }
}
