package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * SSH 终端图片上传表单。
 */
@Data
public class SshImageUploadForm {

    private String workerId;

    private String name;

    private String data;

    private String mimeType;
}
